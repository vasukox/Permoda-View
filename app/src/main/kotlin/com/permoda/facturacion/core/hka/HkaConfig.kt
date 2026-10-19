package com.permoda.facturacion.core.hka

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Configuración de la integración con The Factory HKA.
 *
 * Se construye desde los parámetros XML del CloudLicense (INITIALIZE).
 * Si faltan llaves, se usan los defaults de producción.
 *
 * Defaults de fábrica (producción):
 *  - baseUrl         → https://emision21-api.thefactoryhka.com.co
 *  - timeoutMs       → 15000 (15 s; si la API no responde, el cajero digita
 *                       a mano y el cliente nunca ve la pantalla "validando")
 *  - fallbackMode    → HIBRIDO
 *  - enabled         → true
 *  - cacheMinutes    → 0 (sin cache)
 *  - auditEnabled    → true
 */
data class HkaConfig(
    val baseUrl: String,
    val timeoutMs: Int,
    val fallbackMode: FallbackMode,
    val enabled: Boolean,
    val cacheMinutes: Int,
    val auditEnabled: Boolean
) {

    /** True si la integración está activa y la URL base está bien formada. */
    val isActive: Boolean
        get() = enabled && baseUrl.toHttpUrlOrNull()?.let {
            it.isHttps && it.username.isEmpty() && it.password.isEmpty() && it.query == null && it.fragment == null
        } == true

    enum class FallbackMode { PERMISIVO, CONSERVADOR, HIBRIDO }

    companion object {
        const val URL_DEMO = "https://demoemision21-api.thefactoryhka.com.co"
        const val URL_PRODUCCION = "https://emision21-api.thefactoryhka.com.co"
        const val ENDPOINT_CONSULTA = "/api/adquiriente/consulta"

        /**
         * Tiempo máximo de espera a la DIAN. Pasado este umbral, el flujo
         * cae a formulario manual —el cajero digita a mano y el cliente NO
         * ve la pantalla de "validando" en ningún momento. Cambiado el
         * 14-08-2026 de 5000 a 15000: el endpoint de producción suele
         * tardar entre 8 y 12 s con picos de hasta 14, y el default anterior
         * forzaba fallback demasiado seguido.
         */
        const val DEFAULT_TIMEOUT_MS = 15000
        const val DEFAULT_CACHE_MINUTES = 0
        val DEFAULT_FALLBACK = FallbackMode.HIBRIDO
        const val DEFAULT_ENABLED = true
        const val DEFAULT_AUDIT_ENABLED = true

        /** Defaults listos para usar si INITIALIZE no trajo los parámetros. */
        fun defaults(): HkaConfig = HkaConfig(
            baseUrl = URL_PRODUCCION,
            timeoutMs = DEFAULT_TIMEOUT_MS,
            fallbackMode = DEFAULT_FALLBACK,
            enabled = DEFAULT_ENABLED,
            cacheMinutes = DEFAULT_CACHE_MINUTES,
            auditEnabled = DEFAULT_AUDIT_ENABLED
        )

        /**
         * Parsea los `<Param Key="HKA_*" Value="..."/>` del XML de Parameters
         * recibido en INITIALIZE. Tolera llaves faltantes o malformadas
         * (cae a defaults). Nunca lanza.
         */
        fun fromParametersXml(parametersXml: String?): HkaConfig {
            val def = defaults()
            if (parametersXml.isNullOrBlank()) return def

            val map = com.permoda.facturacion.core.config.ParamMap.parse(parametersXml)
        return def.copy(
            baseUrl       = map["HKA_BASE_URL"]?.takeIf { it.isNotBlank() } ?: def.baseUrl,
            timeoutMs     = map["HKA_TIMEOUT_MS"]?.toIntOrNull()?.coerceIn(1000, 30000) ?: def.timeoutMs,
            fallbackMode  = map["HKA_FALLBACK_MODE"]?.toFallbackMode() ?: def.fallbackMode,
            enabled       = map["HKA_ENABLED"]?.toBoolean() ?: def.enabled,
            cacheMinutes  = map["HKA_CACHE_MINUTES"]?.toIntOrNull()?.coerceIn(0, 1440) ?: def.cacheMinutes,
            auditEnabled  = map["HKA_AUDIT_ENABLED"]?.toBoolean() ?: def.auditEnabled
        )
        }

        private fun String.toFallbackMode(): FallbackMode? = when (uppercase()) {
            "PERMISIVO"   -> FallbackMode.PERMISIVO
            "CONSERVADOR" -> FallbackMode.CONSERVADOR
            "HIBRIDO", "HÍBRIDO" -> FallbackMode.HIBRIDO
            else -> null
        }
    }
}
