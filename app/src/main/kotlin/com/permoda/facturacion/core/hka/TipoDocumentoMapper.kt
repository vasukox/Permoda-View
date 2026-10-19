package com.permoda.facturacion.core.hka

import android.util.Log

/**
 * Conversión de tipos de documento entre el formato de HioPosCloud
 * (alfanumérico: "CC", "NIT", "CE", etc.) y los códigos oficiales de la DIAN
 * (numéricos: "13", "31", "22", etc.).
 *
 * Para mostrar al usuario usamos los **nombres completos**:
 *  - "Cédula de ciudadanía"
 *  - "Tarjeta de identidad"
 *  - "NIT"
 *  - etc.
 *
 * Además mantenemos la tabla de **códigos HioPos** (no DIAN) para cuando
 * hay que enviar el tipo a HioPosCloud (por ejemplo en el campo
 * `CustomerFiscalIdDocType` de `ModifyDocumentResult`). La diferencia
 * importa: HioPos internamente NO usa códigos DIAN, usa los suyos
 * propios. Confirmado en terminal el 13-08-2026:
 *  - cc                 → 9   (DIAN sería 13)
 *  - tarjeta identidad  → 8   (DIAN sería 12)
 *  - registro civil     → 7   (DIAN sería 11)
 *  - cedula extranjera  → 11  (DIAN sería 22)
 *  - nit                → 12  (DIAN sería 31)
 *  - pasaporte          → 65  (DIAN sería 41)
 *  - pep                → 47  (DIAN sería 47, mismo número)
 *  - ptp                → 64  (DIAN sería 48)
 *
 * ⚠️ Tabla propuesta pendiente de confirmación con ICG (Roger).
 */
object TipoDocumentoMapper {

    private const val TAG = "TipoDocumentoMapper"

    /** Nombres **cortos** (legacy, alias que puede mandar HioPosCloud). */
    private val SHORT_TO_FULL = mapOf(
        "RC"   to "Registro civil",
        "TI"   to "Tarjeta de identidad",
        "CC"   to "Cédula de ciudadanía",
        "TE"   to "Tarjeta de extranjería",
        "CE"   to "Cédula de extranjería",
        "NIT"  to "NIT",
        "PAS"  to "Pasaporte",
        "PS"   to "Pasaporte",
        "DE"   to "Documento de identificación extranjero",
        "PEP"  to "PEP",
        // ⚠️ 13-08-2026: el usuario corrigió — el nombre "correcto" para
        // HioPosCloud es "PTP" (no "PPT"). Actualizamos el alias para que
        // `toFullName("PTP")` devuelva el nombre canónico, y los alias
        // cortos acepten tanto "PPT" como "PTP" por compatibilidad con
        // cualquier cosa vieja que mande HioPosCloud.
        "PPT"  to "PTP",
        "PTP"  to "PTP",
        "NE"   to "NIT de otro país",
        "NUIP" to "NUIP"
    )

    /** Nombres completos → código DIAN (2 dígitos). */
    private val FULL_TO_DIAN = SHORT_TO_FULL.entries.associate { (short, full) ->
        // Desduplicamos por full name (PPT y PTP caen al mismo "PTP").
        full to shortToDian(short)
    }

    /**
     * Nombres completos → código HioPos (no DIAN, ver tabla en KDoc).
     *
     * Sólo entran los tipos **confirmados en terminal**: [shortToHioPos] devuelve
     * `null` para los que no lo están y aquí se descartan. Es deliberado — no
     * mandar el tipo es recuperable (el cajero lo corrige en HioPos), mandarlo
     * equivocado no: quedaría un cliente mal tipificado sin que nadie lo note.
     */
    private val FULL_TO_HIOPOS: Map<String, String> = SHORT_TO_FULL.entries
        .mapNotNull { (short, full) -> shortToHioPos(short)?.let { full to it } }
        .toMap()

    /**
     * Código HioPos → nombre completo. Es la inversa de [FULL_TO_HIOPOS] y la
     * usa [fromHioPosCode] para leer el `FiscalIdDocType` que manda el POS.
     *
     * ⚠️ Esta tabla **no** se puede sustituir por [DIAN_TO_FULL]: los dos
     * dominios numéricos se pisan. Ver el aviso de [fromHioPosCode].
     */
    private val HIOPOS_TO_FULL: Map<String, String> = FULL_TO_HIOPOS.entries
        .groupBy({ it.value }, { it.key })
        .mapValues { (_, names) -> names.first() }

    /** Código DIAN → nombre completo. */
    private val DIAN_TO_FULL: Map<String, String> = SHORT_TO_FULL.entries
        .groupBy({ shortToDian(it.key) }, { it.value })
        .mapValues { (_, names) -> names.first() }

    private val CODIGOS_VALIDOS: Set<String> = DIAN_TO_FULL.keys.toSet()

    private fun shortToDian(short: String): String = when (short) {
        "RC"   -> "11"
        "TI"   -> "12"
        "CC"   -> "13"
        "TE"   -> "21"
        "CE"   -> "22"
        "NIT"  -> "31"
        "PAS"  -> "41"
        "PS"   -> "41"
        "DE"   -> "42"
        "PEP"  -> "47"
        "PPT"  -> "48"
        "PTP"  -> "48"
        "NE"   -> "50"
        "NUIP" -> "91"
        else   -> short
    }

    /**
     * Códigos HioPos (no DIAN) — confirmado en terminal C9H el 13-08-2026.
     * Si en el futuro el mapeo se confirma con ICG, este `when` se vuelve
     * la fuente de verdad y se actualiza el envío del doc type en
     * `ModifyDocumentXml`.
     */
    private fun shortToHioPos(short: String): String? = when (short) {
        "RC"   -> "7"
        "TI"   -> "8"
        "CC"   -> "9"
        "CE"   -> "11"
        "NIT"  -> "12"
        "PAS"  -> "65"
        "PS"   -> "65"
        "PEP"  -> "47"
        "PPT"  -> "64"
        "PTP"  -> "64"
        // ⚠️ `TE`, `DE`, `NE` y `NUIP` NO están confirmados y devuelven null a
        // propósito. Antes tenían valores inventados (10, 13, 66, 14) con el
        // comentario "pendiente de confirmar", y uno era activamente peligroso:
        // `DE → 13`, que es el código DIAN de la cédula de ciudadanía.
        //
        // Ninguno es alcanzable desde la pantalla —`supportedFullNames()` sólo
        // ofrece los 8 confirmados— así que devolver null no quita nada y evita
        // tipificar mal a un cliente en silencio. Cuando ICG confirme sus
        // números, se agregan acá y aparecen solos en las dos tablas.
        else   -> null
    }

    /**
     * Devuelve el código HioPos (no DIAN) para el tipo enviado. Acepta:
     *  - Nombre completo: "Cédula de ciudadanía", "PTP", etc.
     *  - Nombre corto legacy: "CC", "TI", "PTP"
     *  - Código DIAN numérico: "13", "31", etc. (lo convierte por el mapeo
     *    de full-name, no por equivalencia DIAN↔HioPos — porque HioPos
     *    usa SU propia tabla numérica, no la DIAN).
     *  - Código HioPos numérico: "9", "12", etc. (lo devuelve tal cual si
     *    está en la lista de soportados).
     *
     * ⚠️ Hay un conflicto en "13": DIAN 13 = CC, pero HioPos 13 = DE.
     * Resolvemos dando PRIORIDAD al atajo DIAN (que es lo que usa ConsentScreen
     * al guardar `fiscalDocumentType`). Si el caller quiere explícitamente el
     * código HioPos de DE, tiene que pasar el nombre "DE" o
     * "Documento de identificación extranjero".
     */
    fun toHioPosCode(value: String?): String? {
        if (value.isNullOrBlank()) return null
        val trimmed = value.trim()

        // Si es código DIAN de 2 dígitos reconocido por nuestro mapeo, traducir
        // a nombre y luego a código HioPos. Esto va PRIMERO porque los códigos
        // DIAN son los que ConsentScreen guarda en `fiscalDocumentType`.
        if (trimmed.matches(Regex("^\\d{2}$"))) {
            // Si el código NO es DIAN reconocido, intentar como HioPos
            // directamente. Esto resuelve el caso "65" (Pasaporte HioPos,
            // no es DIAN) y "13" (DE HioPos, también es DIAN 13 = CC — acá
            // priorizamos CC porque es lo que produce ConsentScreen).
            val full = DIAN_TO_FULL[trimmed]
            if (full != null) {
                return FULL_TO_HIOPOS[full]
            }
            // No es DIAN: ¿es HioPos directo?
            if (trimmed in FULL_TO_HIOPOS.values) return trimmed
            return null
        }

        // Si ya es código HioPos numérico conocido (caso "65", "9", "12", etc.),
        // devolver tal cual.
        if (trimmed in FULL_TO_HIOPOS.values) return trimmed

        // Nombre completo.
        FULL_TO_HIOPOS[trimmed]?.let { return it }

        // Nombre corto legacy.
        SHORT_TO_FULL[trimmed.uppercase()]?.let { full ->
            return FULL_TO_HIOPOS[full]
        }

        safeLogWarn("Tipo de documento no mapeable a código HioPos: '$value'")
        return null
    }

    /**
     * Traduce el número de tipo de documento **de HioPos** al nombre completo.
     *
     * Es la inversa de [toHioPosCode] y existe porque el `<Document>` entrante
     * trae `FiscalIdDocType` con el número **interno de HioPos**, no con el
     * código DIAN. Confirmado leyendo el `<Customer>` en crudo del terminal C9H
     * (13-08-2026, `qa/logs/eliminado-recrear.txt`):
     *
     * ```
     * <CustomerField Key="FiscalIdDocType">9</CustomerField>
     * <CustomerField Key="FiscalId">1011086580</CustomerField>
     * ```
     *
     * ## ⚠️ NUNCA leas este número con [toFullName] ni con [toDianCode]
     *
     * Los dos dominios numéricos se pisan, y tres valores significan cosas
     * distintas en cada uno:
     *
     * | Valor | HioPos | DIAN |
     * |---|---|---|
     * | `11` | Cédula de extranjería | Registro civil |
     * | `12` | **NIT** | Tarjeta de identidad |
     * | `47` | PEP | PEP (coincide) |
     *
     * Y `7`, `8`, `9`, `64`, `65` no son códigos DIAN válidos, así que
     * [toFullName] devuelve `null` para ellos y el selector de la pantalla cae
     * al primer tipo de la lista.
     *
     * Ese era el bug: un cliente con NIT se mostraba como "Tarjeta de
     * identidad", uno con cédula de extranjería como "Registro civil", y el
     * resto como "Cédula de ciudadanía". Si el cliente confirmaba sin fijarse,
     * el tipo equivocado se guardaba.
     *
     * @return el nombre completo, o `null` si el número no es un tipo de HioPos
     *   conocido. El llamador decide qué hacer con eso — [parseDocumentCustomer]
     *   conserva el valor crudo para no perder el dato.
     */
    fun fromHioPosCode(value: String?): String? {
        if (value.isNullOrBlank()) return null
        return HIOPOS_TO_FULL[value.trim()]
    }

    /**
     * Devuelve el código DIAN para el tipo enviado. Acepta:
     *  - Nombre completo: "Cédula de ciudadanía", "Tarjeta de identidad", etc.
     *  - Nombre corto legacy: "CC", "TI", "NIT"
     *  - Código DIAN numérico: "13", "31", etc.
     */
    fun toDianCode(value: String?): String? {
        if (value.isNullOrBlank()) return null
        val trimmed = value.trim()

        // Si ya es código DIAN numérico válido
        if (trimmed.matches(Regex("^\\d{2}$")) && trimmed in CODIGOS_VALIDOS) {
            return trimmed
        }
        // Código numérico genérico
        if (trimmed.matches(Regex("^\\d+$"))) return trimmed

        // Nombre completo
        FULL_TO_DIAN[trimmed]?.let { return it }

        // Nombre corto legacy
        SHORT_TO_FULL[trimmed.uppercase()]?.let { full ->
            return FULL_TO_DIAN[full]
        }

        safeLogWarn("Tipo de documento no mapeado: '$value'")
        return null
    }

    /** True si el código ya viene en formato DIAN válido. */
    fun isValidDianCode(code: String?): Boolean = code != null && code in CODIGOS_VALIDOS

    /** Devuelve la lista de códigos DIAN soportados (útil para validación/log). */
    fun supportedCodes(): Set<String> = CODIGOS_VALIDOS

    /**
     * Tipo de documento → nombre completo para mostrar al cliente.
     *
     * Acepta las tres formas que pueden llegar, no sólo el código DIAN:
     *  - Código DIAN: "13" → "Cédula de ciudadanía"
     *  - Nombre corto legacy: "NIT" → "NIT", "CC" → "Cédula de ciudadanía"
     *  - Nombre completo ya resuelto: se devuelve tal cual
     *
     * Antes sólo resolvía el código numérico, así que cuando HioPosCloud
     * enviaba el alias corto la pantalla mostraba "CC" en crudo y el
     * selector caía al primer tipo de la lista (un NIT se pintaba como
     * cédula de ciudadanía).
     */
    fun toFullName(value: String?): String? {
        if (value.isNullOrBlank()) return null
        val trimmed = value.trim()

        DIAN_TO_FULL[trimmed]?.let { return it }
        if (trimmed in FULL_TO_DIAN) return trimmed
        SHORT_TO_FULL[trimmed.uppercase()]?.let { return it }

        return null
    }

    /**
     * Lista de nombres COMPLETOS en el orden que se muestran al cliente
     * en el selector (Cédula de ciudadanía primero porque es lo más común).
     *
     * ⚠️ 13-08-2026: "PPT" se renombró a "PTP" para coincidir con el
     * nombre canónico de HioPosCloud.
     */
    fun supportedFullNames(): List<String> = listOf(
        "Cédula de ciudadanía",
        "Tarjeta de identidad",
        "NIT",
        "Cédula de extranjería",
        "Pasaporte",
        "Registro civil",
        "PEP",
        "PTP"
    )

    /** Alias legacy: devuelve los nombres completos (mismo contenido). */
    fun supportedShortNames(): List<String> = supportedFullNames()

    /** Log tolerante a entornos sin Android (tests JVM). */
    private fun safeLogWarn(message: String) = runCatching { Log.w(TAG, message) }
}
