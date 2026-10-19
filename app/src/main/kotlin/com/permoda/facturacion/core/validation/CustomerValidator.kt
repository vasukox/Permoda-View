package com.permoda.facturacion.core.validation

import com.permoda.facturacion.core.hka.TipoDocumentoMapper
import com.permoda.facturacion.core.model.CustomerData

/**
 * Reglas de completitud mínima antes de devolver el cliente a HioPosCloud.
 *
 * Existe porque antes el botón "Aceptar" sólo se deshabilitaba mientras un
 * campo estaba en edición: el cliente podía aceptar con nombre y correo
 * vacíos y eso llegaba al POS como un cliente nuevo sin datos utilizables
 * (y sin correo, la DIAN no tiene a dónde enviar la factura electrónica).
 *
 * Lógica pura — sin Android — para que sea testeable en la JVM.
 */
object CustomerValidator {

    /**
     * Qué se considera obligatorio. Se expone como data class para que
     * ajustar la política sea un cambio de una línea (y para poder probar
     * ambas configuraciones sin tocar el código de producción).
     *
     * Desde el 14-08-2026 los 5 campos son obligatorios por defecto:
     * nombre, tipo doc, número doc, correo y teléfono. El teléfono dejó de ser
     * opcional para evitar que el cajero pueda confirmar un cliente sin
     * datos de contacto. Si en el futuro hace falta volver a `requirePhone=false`,
     * se pasa en [Rules] desde el caller; este default no se relaja a la ligera.
     */
    data class Rules(
        val requireEmail: Boolean = true,
        val requirePhone: Boolean = true
    )

    enum class Field(val display: String) {
        NAME("Nombre y apellido"),
        DOC_TYPE("Tipo de documento"),
        DOC_ID("Número de documento"),
        EMAIL("Correo"),
        PHONE("Teléfono")
    }

    enum class Reason { MISSING, INVALID }

    data class Issue(val field: Field, val reason: Reason)

    data class Result(val issues: List<Issue>) {

        val isValid: Boolean get() = issues.isEmpty()

        fun has(field: Field): Boolean = issues.any { it.field == field }

        /** Campos a resaltar en rojo en la tarjeta de datos. */
        val fields: List<Field> get() = issues.map { it.field }

        /**
         * Texto para el hint bajo el botón Aceptar. Separa lo que falta de
         * lo que está mal escrito para que el cliente sepa qué hacer.
         */
        val summary: String
            get() {
                if (isValid) return ""
                val missing = issues.filter { it.reason == Reason.MISSING }.map { it.field.display }
                val invalid = issues.filter { it.reason == Reason.INVALID }.map { it.field.display }
                return buildList {
                    if (missing.isNotEmpty()) add("Faltan: ${missing.joinToString(", ")}")
                    if (invalid.isNotEmpty()) add("Revisa: ${invalid.joinToString(", ")}")
                }.joinToString(" · ")
            }
    }

    // Deliberadamente laxo: valida forma, no existencia del buzón.
    private val EMAIL = Regex("^[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}$")

    /** Tipos cuyo número puede ser alfanumérico (pasaporte, permisos, doc. extranjero). */
    private val ALPHANUMERIC_DIAN_CODES = setOf("41", "42", "47", "48", "50")

    private const val DOC_ID_MIN = 4
    private const val DOC_ID_MAX = 20

    /**
     * Dígitos mínimos del número **local**, sin el indicativo del país.
     *
     * Diez porque es la longitud de un celular colombiano, que es lo que se
     * teclea en el 99% de las ventas — y porque los campos de las dos pantallas
     * ya topan en 10 (`MAX_PHONE`), así que pedir menos dejaba pasar números
     * incompletos que la factura electrónica no puede usar. Antes el mínimo era
     * 7 (heredado de los fijos de siete cifras) y un celular a medio teclear
     * habilitaba el botón Aceptar.
     */
    private const val PHONE_MIN_LOCAL_DIGITS = 10

    /**
     * Tope generoso a propósito: el número extranjero (`+00…`) puede ser más
     * largo y no queremos rechazarlo por longitud.
     */
    private const val PHONE_MAX_LOCAL_DIGITS = 15

    /**
     * Indicativos que puede traer el número, más largo primero.
     *
     * Se cuentan los dígitos **después** de quitarlo: `+57 3176794149` tiene 12
     * dígitos en total pero sólo 10 son el número. Sin esto, un mínimo de 10
     * sobre el total aceptaría `+57 3176794` — siete dígitos, un celular
     * incompleto.
     *
     * Duplica la lista de `PHONE_COUNTRIES` a propósito: ese modelo vive en
     * `core.consent` junto a código de UI, y la validación no debe depender de la
     * capa de presentación. Si aparece un cuarto país hay que tocar los dos
     * sitios, y el test `los indicativos soportados se descuentan` lo señala.
     */
    private val PHONE_DIAL_PREFIXES = listOf("593", "506", "57", "00")

    fun validate(customer: CustomerData, rules: Rules = Rules()): Result {
        val issues = mutableListOf<Issue>()

        // --- Nombre: la etiqueta pide "NOMBRE Y APELLIDO", así que exigimos dos.
        val name = customer.customerName.trim()
        when {
            name.isBlank() -> issues += Issue(Field.NAME, Reason.MISSING)
            !isFullName(name) -> issues += Issue(Field.NAME, Reason.INVALID)
        }

        // --- Tipo de documento: debe mapear a un código DIAN conocido.
        val dianCode = TipoDocumentoMapper.toDianCode(customer.fiscalDocumentType)
        when {
            customer.fiscalDocumentType.isNullOrBlank() ->
                issues += Issue(Field.DOC_TYPE, Reason.MISSING)
            dianCode == null ->
                issues += Issue(Field.DOC_TYPE, Reason.INVALID)
        }

        // --- Número de documento.
        val docId = customer.fiscalId?.trim().orEmpty()
        when {
            docId.isBlank() -> issues += Issue(Field.DOC_ID, Reason.MISSING)
            !isValidDocId(docId, dianCode) -> issues += Issue(Field.DOC_ID, Reason.INVALID)
        }

        // --- Correo: canal de entrega de la factura electrónica.
        val email = customer.email?.trim().orEmpty()
        when {
            email.isBlank() -> if (rules.requireEmail) issues += Issue(Field.EMAIL, Reason.MISSING)
            !EMAIL.matches(email) -> issues += Issue(Field.EMAIL, Reason.INVALID)
        }

        // --- Teléfono: obligatorio, y con los 10 dígitos completos del celular.
        val phone = customer.phone?.trim().orEmpty()
        when {
            phone.isBlank() -> if (rules.requirePhone) issues += Issue(Field.PHONE, Reason.MISSING)
            !isValidPhone(phone) -> issues += Issue(Field.PHONE, Reason.INVALID)
        }

        return Result(issues)
    }

    /** Al menos dos palabras de 2+ caracteres. Rechaza "Juan" y "J P". */
    private fun isFullName(name: String): Boolean =
        name.split(Regex("\\s+")).count { it.length >= 2 } >= 2

    private fun isValidDocId(docId: String, dianCode: String?): Boolean {
        if (docId.length !in DOC_ID_MIN..DOC_ID_MAX) return false
        if (docId.any { it.isWhitespace() }) return false
        return if (dianCode != null && dianCode in ALPHANUMERIC_DIAN_CODES) {
            docId.all { it.isLetterOrDigit() }
        } else {
            // Cédulas y NIT: sólo dígitos. El NIT se envía sin dígito de
            // verificación ni guiones, igual que lo pide The Factory HKA.
            docId.all { it.isDigit() }
        }
    }

    private fun isValidPhone(phone: String): Boolean {
        // Permitimos +, espacios, guiones y paréntesis; nada más.
        if (!phone.all { it.isDigit() || it in " +-()" }) return false
        return localDigitCount(phone) in PHONE_MIN_LOCAL_DIGITS..PHONE_MAX_LOCAL_DIGITS
    }

    /**
     * Dígitos del número sin el indicativo del país.
     *
     * Sólo descuenta el indicativo si el valor viene con `+`: un número tecleado
     * suelto no lleva prefijo, y descontarle los primeros dígitos "por si acaso"
     * convertiría un celular válido en uno corto.
     */
    private fun localDigitCount(phone: String): Int {
        val digits = phone.filter { it.isDigit() }
        if (!phone.trimStart().startsWith("+")) return digits.length
        val dial = PHONE_DIAL_PREFIXES.firstOrNull { digits.startsWith(it) } ?: return digits.length
        return digits.length - dial.length
    }
}
