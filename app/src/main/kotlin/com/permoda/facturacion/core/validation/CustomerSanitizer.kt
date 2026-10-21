package com.permoda.facturacion.core.validation

import com.permoda.facturacion.core.model.CustomerData
import java.text.Normalizer

/**
 * Limpieza automática de los datos del cliente en el formulario.
 *
 * Existe porque el maestro de HioPos trae datos con "suciedad" que nadie ve:
 * espacios al inicio, espacios dobles, tabulaciones, caracteres invisibles
 * copiados de otro sistema. Los espacios no importan y lo invisible no se puede
 * borrar a mano, así que se corrigen solos y el cliente se guarda limpio.
 *
 * Todo carácter especial visible (un `@` o un número en el nombre, una `ñ` en
 * el correo, un punto en la cédula) no se toca: lo señala [CustomerValidator].
 *
 * Lógica pura — sin Android — para que sea testeable en la JVM.
 */
object CustomerSanitizer {

    private val WHITESPACE_RUN = Regex("\\s+")

    fun clean(customer: CustomerData): CustomerData = customer.copy(
        customerName = cleanText(customer.customerName),
        fiscalId = customer.fiscalId?.let(::cleanDocument),
        email = customer.email?.let(::cleanEmail),
        phone = customer.phone?.let { cleanText(it) }
    )

    /** NFC, sin invisibles ni emojis, espacios simples y sin bordes. */
    fun cleanText(value: String): String {
        val nfc = Normalizer.normalize(value, Normalizer.Form.NFC)
        val sb = StringBuilder(nfc.length)
        var i = 0
        while (i < nfc.length) {
            val cp = nfc.codePointAt(i)
            i += Character.charCount(cp)
            when {
                // Tabulaciones, saltos de línea y espacios raros pasan a espacio normal.
                Character.isWhitespace(cp) || Character.isSpaceChar(cp) -> sb.append(' ')
                isInvisibleOrEmoji(cp) -> Unit
                else -> sb.appendCodePoint(cp)
            }
        }
        return sb.toString().replace(WHITESPACE_RUN, " ").trim()
    }

    /** Correo sin espacios en ningún lado y en minúsculas (los buzones no distinguen). */
    fun cleanEmail(value: String): String =
        cleanText(value).replace(" ", "").lowercase()

    /**
     * Documento sin espacios. Puntos, guiones y demás signos NO se quitan: el
     * validador los señala para que el cajero corrija el dato a conciencia.
     */
    fun cleanDocument(value: String): String =
        cleanText(value).replace(" ", "")

    /**
     * Solo lo que el cajero no puede ver ni borrar: controles y caracteres de
     * formato (espacios de ancho cero, marcas de dirección). Los emojis y símbolos
     * visibles se dejan para que el aviso los muestre.
     */
    private fun isInvisibleOrEmoji(cp: Int): Boolean {
        val type = Character.getType(cp)
        return Character.isISOControl(cp) ||
            type == Character.FORMAT.toInt() ||
            cp == 0xFE0F
    }
}
