package com.permoda.facturacion.core.loyalty

import com.permoda.facturacion.core.config.ParamMap

/**
 * Configuración de la pantalla de comunidad **Koaj Lovers**.
 *
 * Se lee de los mismos `<Param Key="..."/>` del CloudLicense que [
 * com.permoda.facturacion.core.hka.HkaConfig], para que operaciones pueda
 * apagar la pantalla o ajustar el auto-avance **sin liberar una versión**.
 *
 * Llaves:
 *  - `KOAJ_ENABLED`       → true/false (default true)
 *  - `KOAJ_TIMEOUT_MS`    → ms de auto-avance. **Default 0 = nunca se cierra sola**
 *  - `KOAJ_ALLOW_DECLINE` → true/false (default true: se muestra "NO ACEPTO")
 *  - `KOAJ_AUDIT_ENABLED` → true/false (default true)
 *
 * ⚠️ `allowDecline` pasó a `true` (HU-HIOPOS). Antes era `false` "por decisión de
 * negocio: la pantalla no se quita hasta que el cliente pulsa Aceptar", pero el
 * mockup que marca aprobó **incluye el botón "NO ACEPTO"**, así que el diseño
 * aprobado es la decisión más reciente y el código se alinea con él.
 *
 * El motivo de fondo también juega a favor: con `allowDecline = false` y
 * `timeoutMs = 0`, mientras la pantalla está visible el Intent sigue sin
 * resolverse y **la caja queda esperando**. Si el cliente se va sin tocar nada,
 * el cajero no puede cerrar la venta. Un cliente sin salida es una caja
 * bloqueada.
 *
 * Se revierte sin liberar versión poniendo `KOAJ_ALLOW_DECLINE=FALSE` en los
 * parámetros de CloudLicense.
 */
data class LoyaltyConfig(
    val enabled: Boolean,
    val timeoutMs: Long,
    val allowDecline: Boolean,
    val auditEnabled: Boolean
) {

    /** True si hay que mostrar la pantalla tras el Aceptar del cliente. */
    val isActive: Boolean get() = enabled

    /** True si el auto-avance está configurado. */
    val hasTimeout: Boolean get() = timeoutMs > 0

    companion object {
        const val DEFAULT_ENABLED = true

        /** 0 = la pantalla espera indefinidamente al Aceptar del cliente. */
        const val DEFAULT_TIMEOUT_MS = 0L

        /** false = el link "Ahora no, gracias" no se muestra. */
        /** true = se muestra "NO ACEPTO", como en el mockup aprobado. */
        const val DEFAULT_ALLOW_DECLINE = true

        const val DEFAULT_AUDIT_ENABLED = true

        private const val MIN_TIMEOUT_MS = 3_000L
        private const val MAX_TIMEOUT_MS = 60_000L

        fun defaults(): LoyaltyConfig = LoyaltyConfig(
            enabled = DEFAULT_ENABLED,
            timeoutMs = DEFAULT_TIMEOUT_MS,
            allowDecline = DEFAULT_ALLOW_DECLINE,
            auditEnabled = DEFAULT_AUDIT_ENABLED
        )

        /** Nunca lanza: cualquier llave ausente o basura cae al default. */
        fun fromParametersXml(parametersXml: String?): LoyaltyConfig {
            val def = defaults()
            val map = ParamMap.parse(parametersXml)
            if (map.isEmpty()) return def

            val rawTimeout = map["KOAJ_TIMEOUT_MS"]?.toLongOrNull()
            return def.copy(
                enabled = map["KOAJ_ENABLED"]?.toBooleanStrictOrNull() ?: def.enabled,
                timeoutMs = when {
                    rawTimeout == null -> def.timeoutMs
                    rawTimeout <= 0L   -> 0L   // 0 = esperar indefinidamente
                    else               -> rawTimeout.coerceIn(MIN_TIMEOUT_MS, MAX_TIMEOUT_MS)
                },
                allowDecline = map["KOAJ_ALLOW_DECLINE"]?.toBooleanStrictOrNull()
                    ?: def.allowDecline,
                auditEnabled = map["KOAJ_AUDIT_ENABLED"]?.toBooleanStrictOrNull() ?: def.auditEnabled
            )
        }

        private fun String.toBooleanStrictOrNull(): Boolean? = when (trim().lowercase()) {
            "true", "1", "yes", "si", "sí" -> true
            "false", "0", "no"             -> false
            else                           -> null
        }
    }
}
