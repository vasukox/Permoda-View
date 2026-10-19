package com.permoda.facturacion.entry

import android.app.Activity
import android.os.Bundle
import com.permoda.facturacion.core.model.ModuleConfig

/** Compatibilidad de resolución: la selección y consulta pertenecen al POS. */
class CustomerSelectionActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            assertFromTrustedCaller()
            finishCancelled("La selección de clientes se realiza en HioPosCloud")
        } catch (e: SecurityException) {
            finishCancelled("Emisor no autorizado")
        }
    }
}

/** Solo para el driver y las pruebas históricas; no sustituye un token de sesión. */
internal fun deriveAuditToken(config: ModuleConfig, caller: String?): String =
    if (config.isInitialized) config.token else "auto-" + (caller ?: "unknown")
