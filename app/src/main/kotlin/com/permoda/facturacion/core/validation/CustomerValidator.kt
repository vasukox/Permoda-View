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

    /**
     * @param detail qué está mal y cómo se corrige, en palabras del cajero. Lo
     *   muestra el bloqueo al totalizar (`TotalizeGate`); los formularios siguen
     *   usando [Result.summary], que sólo nombra los campos.
     */
    data class Issue(val field: Field, val reason: Reason, val detail: String? = null) {
        /** Línea lista para mostrar: "Correo: está vacío." */
        val message: String
            get() = "${this.field.display}: " + (detail ?: if (reason == Reason.MISSING) "está vacío." else "no es válido.")
    }

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

        /**
         * Qué está mal campo por campo y cómo se corrige ("Nombre y apellido:
         * tiene caracteres no permitidos (@)"). Es el texto del aviso del
         * formulario: con solo el nombre del campo el cajero no sabía qué borrar.
         */
        val detail: String
            get() = issues.joinToString("  ·  ") { it.message }
    }

    /**
     * Validación del **formulario**: valida los datos tal como se van a guardar,
     * es decir, después de [CustomerSanitizer.clean]. Así un espacio de más o un
     * carácter invisible del maestro nunca bloquean al cajero; se corrigen solos.
     *
     * Al totalizar se usa [validate] sobre el dato crudo: ahí sí interesa
     * detectar la suciedad que ya está en el maestro.
     */
    fun validateForSave(customer: CustomerData, rules: Rules = Rules()): Result =
        validate(CustomerSanitizer.clean(customer), rules)

    /**
     * Caracteres que admite la parte local de un correo (RFC 5322, "atext" + punto).
     * Se aceptan todos los que el estándar permite; lo que no está aquí (ñ, tildes,
     * comas, paréntesis…) no llega a un buzón real.
     */
    private const val EMAIL_LOCAL_CHARS = "!#$%&'*+-/=?^_`{|}~."

    /** Forma: local@dominio.tld; dominio con letras, números, guiones y puntos. */
    private val EMAIL = Regex(
        "^[A-Za-z0-9!#$%&'*+\\-/=?^_`{|}~]+(\\.[A-Za-z0-9!#$%&'*+\\-/=?^_`{|}~]+)*" +
            "@[A-Za-z0-9]([A-Za-z0-9\\-]*[A-Za-z0-9])?(\\.[A-Za-z0-9]([A-Za-z0-9\\-]*[A-Za-z0-9])?)*\\.[A-Za-z]{2,}$"
    )

    /** Los espacios no importan en ningún campo: se ignoran antes de validar. */
    private val WHITESPACE = Regex("\\s+")

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

    /** Código DIAN del NIT: razón social, admite números y signos de empresa. */
    private const val NIT_DIAN_CODE = "31"

    /** Signos admitidos en el nombre de una persona, además de letras y espacios. */
    private const val PERSON_NAME_SIGNS = ".'-"

    /** Signos admitidos en una razón social (NIT): "A&B S.A.S.", "Inversiones 2020, Ltda." */
    private const val COMPANY_NAME_SIGNS = ".'-&,"

    fun validate(customer: CustomerData, rules: Rules = Rules()): Result {
        val issues = mutableListOf<Issue>()

        // El tipo se resuelve primero porque decide qué caracteres admite el nombre.
        val dianCode = TipoDocumentoMapper.toDianCode(customer.fiscalDocumentType)

        // --- Nombre: solo caracteres de nombre. Los espacios (dobles, al inicio,
        // tabulaciones) no cuentan: se reducen a uno antes de revisar.
        val name = java.text.Normalizer.normalize(customer.customerName, java.text.Normalizer.Form.NFC)
            .replace(WHITESPACE, " ").trim()
        when {
            name.isBlank() -> issues += Issue(Field.NAME, Reason.MISSING)
            else -> nameProblem(name, isCompany = dianCode == NIT_DIAN_CODE)
                ?.let { issues += Issue(Field.NAME, Reason.INVALID, it) }
        }

        // --- Tipo de documento: debe mapear a un código DIAN conocido.
        when {
            customer.fiscalDocumentType.isNullOrBlank() ->
                issues += Issue(Field.DOC_TYPE, Reason.MISSING)
            dianCode == null ->
                issues += Issue(Field.DOC_TYPE, Reason.INVALID, "no es un tipo de documento reconocido por la DIAN.")
        }

        // --- Número de documento.
        val docId = customer.fiscalId.orEmpty().replace(WHITESPACE, "")
        when {
            docId.isBlank() -> issues += Issue(Field.DOC_ID, Reason.MISSING)
            else -> docIdProblem(docId, dianCode)?.let { issues += Issue(Field.DOC_ID, Reason.INVALID, it) }
        }

        // --- Correo: canal de entrega de la factura electrónica.
        val email = customer.email.orEmpty().replace(WHITESPACE, "")
        when {
            email.isBlank() -> if (rules.requireEmail) {
                issues += Issue(Field.EMAIL, Reason.MISSING, "está vacío; la factura electrónica se envía a ese correo.")
            }
            else -> emailProblem(email)?.let { issues += Issue(Field.EMAIL, Reason.INVALID, it) }
        }

        // --- Teléfono: obligatorio, y con los 10 dígitos completos del celular.
        val phone = customer.phone.orEmpty().replace(WHITESPACE, "")
        when {
            phone.isBlank() -> if (rules.requirePhone) issues += Issue(Field.PHONE, Reason.MISSING)
            else -> phoneProblem(phone)?.let { issues += Issue(Field.PHONE, Reason.INVALID, it) }
        }

        return Result(issues)
    }

    /** `null` si el nombre sirve; si no, qué tiene mal. */
    private fun nameProblem(name: String, isCompany: Boolean): String? {
        val signs = if (isCompany) COMPANY_NAME_SIGNS else PERSON_NAME_SIGNS
        val bad = name.codePoints().toArray()
            .filterNot { cp ->
                cp == ' '.code ||
                    (Character.isLetter(cp) && Character.UnicodeScript.of(cp) == Character.UnicodeScript.LATIN) ||
                    (isCompany && Character.isDigit(cp) && cp < 128) ||
                    (cp < 128 && cp.toChar() in signs)
            }
        if (bad.isNotEmpty()) {
            val allowed = if (isCompany) "letras, números, espacios y . , ' - &" else "letras, espacios y . ' -"
            return "tiene caracteres especiales no permitidos (${describeChars(bad)}). Solo se aceptan $allowed."
        }
        return null
    }

    private fun docIdProblem(docId: String, dianCode: String?): String? {
        val alphanumeric = dianCode != null && dianCode in ALPHANUMERIC_DIAN_CODES
        // Cédulas y NIT: exclusivamente dígitos. El NIT se envía sin dígito de
        // verificación ni guiones, igual que lo pide The Factory HKA. Pasaporte
        // y documentos extranjeros: letras y dígitos.
        val ok: (Int) -> Boolean = if (alphanumeric) {
            { it in '0'.code..'9'.code || it in 'A'.code..'Z'.code || it in 'a'.code..'z'.code }
        } else {
            { it in '0'.code..'9'.code }
        }
        val bad = docId.codePoints().toArray().filterNot(ok)
        if (bad.isNotEmpty()) {
            val allowed = if (alphanumeric) "letras y números" else "números"
            return "tiene caracteres especiales no permitidos (${describeChars(bad)}). Solo se aceptan $allowed."
        }
        if (docId.length !in DOC_ID_MIN..DOC_ID_MAX) return "debe tener entre $DOC_ID_MIN y $DOC_ID_MAX caracteres."
        return null
    }

    private fun emailProblem(email: String): String? {
        val bad = email.codePoints().toArray()
            .filterNot { it < 128 && (it.toChar().isLetterOrDigit() || it.toChar() in EMAIL_LOCAL_CHARS || it == '@'.code) }
        if (bad.isNotEmpty()) {
            return "tiene caracteres especiales no permitidos (${describeChars(bad)}). " +
                "Solo se aceptan letras sin tilde, números y . _ - + ! # $ % & ' * / = ? ^ ` { | } ~"
        }
        if (email.count { it == '@' } != 1 || !EMAIL.matches(email)) {
            return "formato inválido; debe ser como nombre@dominio.com."
        }
        return null
    }

    private fun phoneProblem(phone: String): String? {
        // Exclusivamente números; el + solo al inicio, para el indicativo.
        val bad = phone.codePoints().toArray().withIndex()
            .filterNot { (i, cp) -> cp in '0'.code..'9'.code || (i == 0 && cp == '+'.code) }
            .map { it.value }
        if (bad.isNotEmpty()) {
            return "tiene caracteres especiales no permitidos (${describeChars(bad)}). Solo se aceptan números."
        }
        if (localDigitCount(phone) !in PHONE_MIN_LOCAL_DIGITS..PHONE_MAX_LOCAL_DIGITS) {
            return "debe tener $PHONE_MIN_LOCAL_DIGITS dígitos sin contar el indicativo."
        }
        return null
    }

    /**
     * Los caracteres rechazados, legibles para el cajero: los invisibles se
     * nombran porque mostrarlos tal cual no dejaría ver qué quitar.
     */
    private fun describeChars(codePoints: List<Int>): String = codePoints.distinct().take(5).joinToString(" ") { cp ->
        when {
            cp == ' '.code -> "espacio"
            cp == '\t'.code -> "tabulación"
            cp == '\n'.code || cp == '\r'.code -> "salto de línea"
            Character.isWhitespace(cp) || Character.isSpaceChar(cp) -> "espacio especial"
            Character.getType(cp) == Character.FORMAT.toInt() || Character.isISOControl(cp) -> "carácter invisible"
            else -> String(Character.toChars(cp))
        }
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
