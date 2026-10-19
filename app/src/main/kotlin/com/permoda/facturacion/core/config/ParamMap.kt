package com.permoda.facturacion.core.config

/**
 * Parser de los `<Param Key="..." Value="..."/>` del XML de Parameters que
 * HioPosCloud entrega en INITIALIZE.
 *
 * Extraído de `HkaConfig` para que otras configuraciones (p.ej.
 * [com.permoda.facturacion.core.loyalty.LoyaltyConfig]) lean del mismo XML
 * sin duplicar los regex (DRY).
 *
 * Tolera ambos formatos y nunca lanza:
 *  - `<Param Key="X" Value="Y"/>`
 *  - `<Param Key="X">Y</Param>`
 */
object ParamMap {

    private val ATTR_FORM = Regex(
        pattern = "<Param\\s+[^>]*?Key=\"([^\"]+)\"[^>]*?Value=\"([^\"]*)\"[^>]*?/?>",
        option = RegexOption.IGNORE_CASE
    )

    private val CHILD_FORM = Regex(
        pattern = "<Param\\s+[^>]*?Key=\"([^\"]+)\"[^>]*?>([^<]*)</Param>",
        option = RegexOption.IGNORE_CASE
    )

    fun parse(xml: String?): Map<String, String> {
        if (xml.isNullOrBlank()) return emptyMap()
        val out = mutableMapOf<String, String>()

        ATTR_FORM.findAll(xml).forEach { match ->
            val key = match.groupValues[1]
            if (key.isNotBlank()) out[key] = match.groupValues[2]
        }
        // El formato hijo sólo rellena llaves que el formato atributo no trajo.
        CHILD_FORM.findAll(xml).forEach { match ->
            val key = match.groupValues[1]
            if (key.isNotBlank() && key !in out) out[key] = match.groupValues[2].trim()
        }

        return out
    }
}
