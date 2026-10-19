package com.permoda.facturacion.core

import com.permoda.facturacion.core.model.CustomerData
import com.permoda.facturacion.core.model.CustomerField
import com.permoda.facturacion.core.scenario.CustomerScenario
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Parser del XML `CustomerResult` que envía HioPosCloud.
 *
 * - Usa el DOM estándar de Java (funciona en Android y en unit tests JVM).
 * - Bloquea XXE: desactiva entidades externas y DOCTYPE.
 * - Limita tamaño de entrada como defensa básica.
 *
 * Reglas de parseo:
 *  - La raíz puede ser `<CustomerResult>` (envoltura) o `<Customer>` (sin envoltura).
 *  - Cada dato viene como `<CustomerField Key="...">valor</CustomerField>`.
 *  - Los 5 campos pintados son: customerName, fiscalDocumentType, fiscalId, email, phone.
 *  - El resto de campos se conservan en `extraFieldsXml` para reenviarlos al POS.
 */
object Xml {

    private const val MAX_BYTES = 256 * 1024

    private val builder by lazy {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false
            isValidating = false
            isExpandEntityReferences = false
            // Endurecimiento XML. Algunas features son específicas de Xerces
            // y Android (Harmony) las rechaza con ParserConfigurationException;
            // las envolvemos en try/catch para no romper la app en runtimes
            // que no las soportan.
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        }
        factory.newDocumentBuilder()
    }

    /**
     * Parsea el XML del cliente. Devuelve un [ParsedCustomer] con los
     * datos para pintar y el `extraFieldsXml` para reenviar al POS.
     */
    fun parseCustomer(xml: String): ParsedCustomer {
        require(xml.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "XML excede el tamaño máximo" }

        val root = builder.parse(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8))).documentElement
        val customer: Element = if (root.nodeName == "Customer") root
            else root.firstChildByName("Customer") ?: root

        val fields = customer.childrenByName("CustomerField")
            .map { CustomerField(it.getAttribute("Key").orEmpty(), it.text()) }
            .filter { it.key.isNotEmpty() }

        fun valueOf(key: String): String? =
            fields.firstOrNull { it.key == key }?.value

        val data = CustomerData(
            customerId          = valueOf("customerId"),
            customerName        = valueOf("customerName").orEmpty(),
            fiscalDocumentType  = valueOf("fiscalDocumentType"),
            fiscalId            = valueOf("fiscalId"),
            email               = valueOf("email"),
            phone               = valueOf("phone"),
            countryIso2         = valueOf(KEY_COUNTRY_ISO2)
        )

        val extras = fields
            .filterNot { it.key in DISPLAYED_KEYS }
            .joinToString("") { f -> "<CustomerField Key=\"${escape(f.key)}\">${escape(f.value)}</CustomerField>" }

        return ParsedCustomer(data = data, extraFieldsXml = extras)
    }

    /**
     * Reconstruye el `CustomerResult` para devolver a HioPosCloud,
     * preservando el orden y los campos no mostrados.
     *
     * @param loyaltyOptIn decisión del cliente sobre Koaj Lovers. Se emite
     *   siempre (`true`/`false`) y nunca se omite: un campo ausente sería
     *   ambiguo entre "declinó" y "no se le preguntó". `null` sólo cuando la
     *   pantalla está deshabilitada por configuración, o Consumidor Final; en
     *   ese caso no se escribe el campo.
     * @param scenario escenario con el que se resolvió. HioPosCloud lo usa
     *   para saber si tiene que crear el cliente o si es el genérico.
     */
    fun writeCustomerResult(
        data: CustomerData,
        extraFieldsXml: String,
        loyaltyOptIn: Boolean? = null,
        scenario: CustomerScenario? = null,
        invoicing: Boolean = true
    ): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
        append("<CustomerResult>")
        // Campos pintados, en orden contractual.
        writeField("customerId", data.customerId)
        writeField("customerName", data.customerName)
        writeField("fiscalDocumentType", data.fiscalDocumentType)
        writeField("fiscalId", data.fiscalId)
        writeField("email", data.email)
        writeField("phone", data.phone)
        // Campos del contrato que antes no escribíamos: sólo sobrevivían si
        // HioPosCloud los había mandado en la entrada.
        // Se respeta el país que vino; sólo cae a CO si no venía.
        writeField(
            KEY_COUNTRY_ISO2,
            data.countryIso2?.trim()?.takeIf { it.isNotBlank() } ?: DEFAULT_COUNTRY
        )
        writeField(KEY_IS_INVOICE, invoicing.toString())
        writeField(KEY_ELECTRONIC_INVOICE, invoicing.toString())
        // Campos propios de PermodaView. Van al final para no desordenar los
        // contractuales.
        loyaltyOptIn?.let { writeField(KEY_LOYALTY_OPT_IN, it.toString()) }
        scenario?.let {
            writeField(KEY_SCENARIO, it.name)
            writeField(KEY_IS_CONSUMIDOR_FINAL, (it == CustomerScenario.CONSUMIDOR_FINAL).toString())
        }
        // Resto (preservar lo que vino, sin reordenar).
        append(extraFieldsXml)
        append("</CustomerResult>")
    }


    /** Lee la decisión previa de Koaj Lovers si el POS ya la tenía guardada. */
    fun readLoyaltyOptIn(xml: String): Boolean? = runCatching {
        val root = builder.parse(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8))).documentElement
        val customer: Element = if (root.nodeName == "Customer") root
            else root.firstChildByName("Customer") ?: root
        customer.childrenByName("CustomerField")
            .firstOrNull { it.getAttribute("Key") == KEY_LOYALTY_OPT_IN }
            ?.text()
            ?.trim()
            ?.lowercase()
            ?.let { it == "true" || it == "1" }
    }.getOrNull()

    private fun StringBuilder.writeField(key: String, value: String?) {
        append("<CustomerField Key=\"").append(escape(key)).append("\">")
        append(escape(value.orEmpty()))
        append("</CustomerField>")
    }

    private fun escape(value: String) = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

    /** Campo propio de PermodaView: opt-in a la comunidad Koaj Lovers. */
    const val KEY_LOYALTY_OPT_IN = "koajLoversOptIn"

    /** Campo propio: escenario con el que se resolvió la selección. */
    const val KEY_SCENARIO = "permodaScenario"

    /** Campo propio: atajo booleano para que el POS no parsee el enum. */
    const val KEY_IS_CONSUMIDOR_FINAL = "isConsumidorFinal"

    // Campos del contrato de ICG que PermodaView escribe siempre.
    const val KEY_COUNTRY_ISO2 = "countryIso2"

    /**
     * HU-HIOPOS · Antes esto era `generateInvoice`, una llave que **no existe en
     * el contrato**: la estructura `CustomerResult` (HPCL.025, págs. 39-40)
     * define `isInvoice`. HioPosCloud descartaba el campo, así que el flag de
     * "esta selección es para una factura" —que nos llega en el extra
     * `Invoicing` del Intent— nunca llegaba de vuelta al POS.
     *
     * `XmlTest.no escribimos llaves que el contrato no define` impide que
     * vuelva a pasar con cualquier llave nueva.
     */
    const val KEY_IS_INVOICE = "isInvoice"
    const val KEY_ELECTRONIC_INVOICE = "electronicInvoice"

    /** Colombia. Sólo se usa si el POS no mandó país. */
    const val DEFAULT_COUNTRY = "CO"

    /**
     * Llave que escribíamos por error antes de HU-HIOPOS. No se escribe más;
     * solo se conserva para poder descartarla si vuelve del POS.
     */
    const val LEGACY_KEY_GENERATE_INVOICE = "generateInvoice"

    /**
     * Campos que este objeto escribe explícitamente. Se excluyen de
     * `extraFieldsXml` para que un round-trip (EDIT de un cliente que ya
     * traía `koajLoversOptIn` o `countryIso2`) no los duplique en la salida.
     */
    private val DISPLAYED_KEYS = setOf(
        "customerId", "customerName", "fiscalDocumentType",
        "fiscalId", "email", "phone",
        KEY_COUNTRY_ISO2, KEY_IS_INVOICE, KEY_ELECTRONIC_INVOICE,
        KEY_LOYALTY_OPT_IN, KEY_SCENARIO, KEY_IS_CONSUMIDOR_FINAL,
        // Se sigue filtrando de `extraFieldsXml` aunque ya no lo escribimos: un
        // cliente guardado por una versión anterior puede traerlo en el maestro
        // del POS, y reenviarlo solo propagaría la llave equivocada (HU-HIOPOS).
        LEGACY_KEY_GENERATE_INVOICE
    )
}

/** Resultado de parsear un CustomerResult. */
data class ParsedCustomer(
    val data: CustomerData,
    val extraFieldsXml: String
)

// --- Helpers DOM ---

private fun Element.text(): String {
    val sb = StringBuilder()
    val children = childNodes
    for (i in 0 until children.length) {
        val node = children.item(i)
        if (node.nodeType == Node.TEXT_NODE || node.nodeType == Node.CDATA_SECTION_NODE) {
            sb.append(node.nodeValue.orEmpty())
        }
    }
    return sb.toString().trim()
}

private fun Element.firstChildByName(name: String): Element? {
    val children = childNodes
    for (i in 0 until children.length) {
        val node = children.item(i)
        if (node.nodeType == Node.ELEMENT_NODE && node.nodeName == name) {
            return node as Element
        }
    }
    return null
}

private fun Element.childrenByName(name: String): List<Element> {
    val children = childNodes
    val out = mutableListOf<Element>()
    for (i in 0 until children.length) {
        val node = children.item(i)
        if (node.nodeType == Node.ELEMENT_NODE && node.nodeName == name) {
            out += node as Element
        }
    }
    return out
}
