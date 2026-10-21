package com.permoda.facturacion.core.manage

import com.permoda.facturacion.core.ParsedCustomer
import com.permoda.facturacion.core.config.ParamMap
import com.permoda.facturacion.core.customer.CustomerType
import com.permoda.facturacion.core.customer.CustomerTypeResolver
import com.permoda.facturacion.core.hka.TipoDocumentoMapper
import com.permoda.facturacion.core.model.CustomerData
import com.permoda.facturacion.core.validation.CustomerSanitizer
import com.permoda.facturacion.core.validation.CustomerValidator
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import javax.xml.parsers.DocumentBuilderFactory

enum class CustomerOperation { CREATE, EDIT }

/** IDs del catálogo remoto. Los IDs del enum de UI nunca se envían directamente. */
data class CustomerCategoryMapping(val normal: Int, val vip: Int, val employee: Int) {
    init {
        require(listOf(normal, vip, employee).all { it >= 0 }) { "Los IDs de categoría deben ser no negativos" }
        require(setOf(normal, vip, employee).size == 3) { "Las categorías deben tener IDs distintos" }
    }

    /** `null` si el ID no es del catálogo (p. ej. 0 = sin tipo asignado en HioPos). */
    fun fromRemote(id: Int): CustomerType? = when (id) {
        normal -> CustomerType.ESTANDAR
        vip -> CustomerType.VIP
        employee -> CustomerType.EMPLEADO
        else -> null
    }

    fun toRemote(type: CustomerType): Int = when (type) {
        CustomerType.ESTANDAR -> normal
        CustomerType.VIP -> vip
        CustomerType.EMPLEADO -> employee
    }
}

enum class FiscalCodeDomain { HIOPOS, DIAN }

/**
 * Perfil explícito del borde. CustomerResult como entrada es la convención heredada,
 * no un literal verificado de MANAGE_CUSTOMER. CloudLicense puede ajustar los nombres
 * sin recompilar. Sin identidad o catálogo inequívocos se cancela; no se adivina por datos.
 *
 * `categories == null` es el modo de prueba sin catálogo (solo debug): el customerTypeId
 * recibido se devuelve intacto y Koaj no cambia la categoría.
 */
data class ManageCustomerConfig(
    val categories: CustomerCategoryMapping?,
    val inputExtra: String = "CustomerResult",
    val inputUriExtra: String = "CustomerResultUri",
    val identityExtra: String = "customerId",
    val fiscalCodes: FiscalCodeDomain = FiscalCodeDomain.HIOPOS
) {
    init {
        require(listOf(inputExtra, inputUriExtra, identityExtra).all { it.matches(Regex("[A-Za-z][A-Za-z0-9_]{0,79}")) }) {
            "Nombre de extra inválido en el perfil ManageCustomer"
        }
        require(setOf(inputExtra, inputUriExtra, identityExtra).size == 3) { "Los extras del perfil deben ser distintos" }
    }

    companion object {
        private val CATEGORY_KEYS = listOf("CUSTOMER_TYPE_NORMAL_ID", "CUSTOMER_TYPE_VIP_ID", "CUSTOMER_TYPE_EMPLOYEE_ID")

        /** Catálogo de tipos de cliente de Permoda en HioOffice: 1 Estándar, 2 VIP, 3 Empleado. */
        val PERMODA_CATEGORIES = CustomerCategoryMapping(normal = 1, vip = 2, employee = 3)

        /**
         * Sin las tres claves se usa [PERMODA_CATEGORIES]; CloudLicense puede sobrescribirlas.
         * Una configuración parcial sigue fallando: mezclar defaults con IDs ajenos sería ambiguo.
         */
        fun fromParametersXml(xml: String): ManageCustomerConfig {
            val p = ParamMap.parse(xml)
            fun id(key: String): Int = p[key]?.trim()?.toIntOrNull()
                ?: throw IllegalArgumentException("Falta configurar $key en CloudLicense")
            val useDefaults = CATEGORY_KEYS.all { p[it].isNullOrBlank() }
            return ManageCustomerConfig(
                categories = if (useDefaults) PERMODA_CATEGORIES
                    else CATEGORY_KEYS.map(::id).let { (n, v, e) -> CustomerCategoryMapping(n, v, e) },
                inputExtra = p["MANAGE_CUSTOMER_INPUT_EXTRA"] ?: "CustomerResult",
                inputUriExtra = p["MANAGE_CUSTOMER_INPUT_URI_EXTRA"] ?: "CustomerResultUri",
                identityExtra = p["MANAGE_CUSTOMER_ID_EXTRA"] ?: "customerId",
                fiscalCodes = p["MANAGE_CUSTOMER_DOCUMENT_CODES"]?.let {
                    runCatching { FiscalCodeDomain.valueOf(it.trim().uppercase()) }.getOrElse {
                        throw IllegalArgumentException("MANAGE_CUSTOMER_DOCUMENT_CODES debe ser HIOPOS o DIAN")
                    }
                } ?: FiscalCodeDomain.HIOPOS
            )
        }
    }
}

/** Identidad congelada de la llamada, independiente del número fiscal editable. */
class ManagedCustomerSession private constructor(
    val operation: CustomerOperation,
    val parsed: ParsedCustomer,
    private val originalId: String?,
    private val fields: Map<String, String>,
    private val config: ManageCustomerConfig,
    /** El tipo recibido es del catálogo. Si no, se trata como Estándar sin sobrescribirlo. */
    private val incomingTypeKnown: Boolean
) {
    val shouldQueryHka: Boolean get() = operation == CustomerOperation.CREATE

    /** La entrada al alta ya fue autorizada en el POS. Nunca hereda un ID provisional. */
    fun forDocument(documentType: String, document: String): CustomerData {
        check(shouldQueryHka) { "HKA no está permitido durante una edición" }
        val same = parsed.data.fiscalId?.trim() == document.trim()
        return parsed.data.copy(customerId = null, fiscalDocumentType = documentType, fiscalId = document.trim(),
            customerName = if (same) parsed.data.customerName else "",
            email = if (same) parsed.data.email else "", phone = if (same) parsed.data.phone else "")
    }

    fun resultXml(input: CustomerData, loyaltyOptIn: Boolean?): String {
        // Se guarda limpio: espacios de más e invisibles del maestro se corrigen aquí.
        val customer = CustomerSanitizer.clean(input)
        require(CustomerValidator.validate(customer).isValid) { "Completa los datos obligatorios del cliente" }
        val category = CustomerTypeResolver.resolve(parsed.data.customerTypeId, null, loyaltyOptIn, false)
        val values = fields.toMutableMap()
        // No propagar etiquetas internas del flujo anterior como datos del maestro.
        listOf("customerId", "koajLoversOptIn", "permodaScenario", "isConsumidorFinal", "generateInvoice").forEach(values::remove)
        originalId?.let { values["customerId"] = it }
        values["customerName"] = customer.customerName.trim()
        values["fiscalId"] = customer.fiscalId.orEmpty().trim()
        values["fiscalDocumentType"] = when (config.fiscalCodes) {
            FiscalCodeDomain.HIOPOS -> TipoDocumentoMapper.toHioPosCode(customer.fiscalDocumentType)
            FiscalCodeDomain.DIAN -> TipoDocumentoMapper.toDianCode(customer.fiscalDocumentType)
        } ?: throw IllegalArgumentException("Tipo de documento no soportado por el POS")
        values["email"] = customer.email.orEmpty().trim()
        values["phone"] = customer.phone.orEmpty().trim()
        values["countryIso2"] = customer.countryIso2?.takeIf { it.isNotBlank() } ?: "CO"
        // Sin catálogo se conserva el valor recibido (o se omite en un alta sin categoría).
        // Una edición con tipo fuera del catálogo (o sin tipo) conserva lo que traía: solo
        // aceptar Koaj Lovers lo cambia. Así editar nunca inventa una categoría.
        config.categories?.let { c ->
            val keepIncoming = operation == CustomerOperation.EDIT && !incomingTypeKnown && category != CustomerType.VIP
            if (!keepIncoming) values["customerTypeId"] = c.toRemote(category).toString()
        }
        // contactTypeId, allowPromos y demás campos originales se preservan, sin
        // confundir naturaleza fiscal o permisos comerciales con la membresía.
        return buildString {
            append("<?xml version=\"1.0\" encoding=\"UTF-8\"?><CustomerResult>")
            values.forEach { (key, value) ->
                append("<CustomerField Key=\"").append(escape(key)).append("\">")
                append(escape(value)).append("</CustomerField>")
            }
            // HioPos 15.14.3 (XMLBuilder.addCustomerSearchResultFromXML) compara "searchField" y
            // "searchValue" con minúscula inicial; el "SearchField" del PDF 0.6.4 se ignora.
            append("<CustomerSearch><CustomerSearchField Key=\"searchField\">FiscalId</CustomerSearchField>")
            append("<CustomerSearchField Key=\"searchValue\">").append(escape(values.getValue("fiscalId")))
            append("</CustomerSearchField></CustomerSearch></CustomerResult>")
        }
    }

    companion object {
        const val MAX_BYTES = 256 * 1024

        fun parse(xml: String, config: ManageCustomerConfig, identity: String? = null): ManagedCustomerSession {
            require(xml.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "XML de cliente demasiado grande" }
            require(!Regex("<!\\s*(DOCTYPE|ENTITY)", RegexOption.IGNORE_CASE).containsMatchIn(xml)) { "Declaraciones XML no permitidas" }
            val factory = DocumentBuilderFactory.newInstance().apply {
                isExpandEntityReferences = false
                runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
                runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
                runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
            }
            val root = factory.newDocumentBuilder().parse(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8))).documentElement
            require(root.nodeName == "CustomerResult" || root.nodeName == "Customer") { "Se esperaba CustomerResult o Customer" }
            val fields = linkedMapOf<String, String>()
            for (i in 0 until root.childNodes.length) {
                val e = root.childNodes.item(i) as? Element ?: continue
                require(e.nodeName == "CustomerField") { "Elemento inesperado en el cliente" }
                val key = e.getAttribute("Key")
                require(key.isNotBlank() && key !in fields) { "Campo de cliente vacío o duplicado" }
                require((0 until e.childNodes.length).none { e.childNodes.item(it) is Element }) { "Campo de cliente anidado" }
                fields[key] = e.textContent.trim()
            }
            fun id(value: String): Long = value.trim().toLongOrNull()
                ?: throw IllegalArgumentException("Identidad de cliente inválida")
            val xmlId = fields["customerId"]?.takeIf { it.isNotBlank() }?.let(::id)
            val extraId = identity?.let(::id)
            require(xmlId == null || extraId == null || xmlId == extraId) { "Identidades de cliente contradictorias" }
            val incomingId = xmlId ?: extraId
                ?: throw IllegalArgumentException("Falta identidad explícita de alta/edición en ManageCustomer")
            val operation = if (incomingId > 0) CustomerOperation.EDIT else CustomerOperation.CREATE
            return build(fields, operation, incomingId, config)
        }

        /** Claves que HioPos 15.14.3 envía como extras sueltos en DocumentAPI.manageCustomer. */
        val POS_FIELD_KEYS = listOf(
            "fiscalId", "email", "phone", "customerName", "fiscalDocumentType", "contactTypeId", "address",
            "postalCode", "city", "state", "mobilePhone", "birthDate", "allowPromos", "customerTypeId"
        )

        /**
         * HioPos 15.14.3 no envía XML ni customerId. En un alta (`isNew`) solo pone fiscalId,
         * email y phone; en una edición añade la ficha completa. La operación sale de esa forma
         * de la llamada, que es el propio flag del POS, no de los valores personales.
         */
        fun fromPosExtras(extras: Map<String, Any?>, config: ManageCustomerConfig): ManagedCustomerSession {
            val fields = linkedMapOf<String, String>()
            POS_FIELD_KEYS.forEach { key -> extras[key]?.let { fields[key] = posValue(it) } }
            require(POS_FIELD_KEYS.any(extras::containsKey)) { "Falta el cliente en los extras de ManageCustomer" }
            val operation = if (extras.containsKey("customerName")) CustomerOperation.EDIT else CustomerOperation.CREATE
            return build(fields, operation, null, config)
        }

        private fun build(
            fields: Map<String, String>, operation: CustomerOperation, incomingId: Long?, config: ManageCustomerConfig
        ): ManagedCustomerSession {
            // Un cliente sin tipo, con 0 o con un tipo fuera del catálogo se puede editar: se
            // muestra como Estándar y resultXml conserva el valor original.
            val remoteType = fields["customerTypeId"]?.trim()?.toIntOrNull()
            val known = config.categories?.let { c -> remoteType?.let(c::fromRemote) }
            val type = known ?: CustomerType.ESTANDAR
            val rawDocType = fields["fiscalDocumentType"]
            val docType = when (config.fiscalCodes) {
                FiscalCodeDomain.HIOPOS -> TipoDocumentoMapper.fromHioPosCode(rawDocType)
                    ?: TipoDocumentoMapper.toFullName(rawDocType?.takeUnless { it.all(Char::isDigit) })
                FiscalCodeDomain.DIAN -> TipoDocumentoMapper.toFullName(rawDocType)
            }
            // El formulario abre con los datos ya limpios: el cajero ve cómo quedarán guardados.
            val data = CustomerSanitizer.clean(CustomerData(
                customerId = incomingId?.takeIf { it > 0 }?.toString(),
                customerName = fields["customerName"].orEmpty(),
                fiscalDocumentType = docType,
                fiscalId = fields["fiscalId"], email = fields["email"], phone = fields["phone"],
                countryIso2 = fields["countryIso2"], customerTypeId = type.id
            ))
            return ManagedCustomerSession(operation, ParsedCustomer(data, ""), data.customerId, fields, config, known != null)
        }

        /**
         * HioPos manda `birthDate` como `java.sql.Date` y lo vuelve a leer con `yyyy-MM-dd`.
         * Se formatea explícitamente para que un `java.util.Date` no viaje como
         * "Tue Apr 18 00:00:00 GMT-05:00 1990", que el POS no sabe leer.
         */
        private fun posValue(value: Any): String = when (value) {
            is java.util.Date -> java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(value)
            else -> value.toString().trim()
        }

        private fun escape(s: String): String = s.replace("&", "&amp;").replace("<", "&lt;")
            .replace(">", "&gt;").replace("\"", "&quot;")
    }
}
