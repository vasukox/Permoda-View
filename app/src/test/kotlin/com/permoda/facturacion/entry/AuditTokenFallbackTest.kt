package com.permoda.facturacion.entry

import com.permoda.facturacion.core.model.ModuleConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * HU-HIOPOS · Test del fallback de `auditToken` cuando HioPosCloud
 * dispara `SELECT_CUSTOMER`/`MODIFY_DOCUMENT` directo sin haber
 * enviado `INITIALIZE` antes.
 *
 * El cambio en producción fue quitar la precondición dura en
 * `CustomerSelectionActivity.onCreate` para que el flujo del cliente
 * no quede bloqueado. Esto NO debilita la auditoría: el token se
 * deriva del `caller` en lugar del UUID de INITIALIZE, pero sigue
 * siendo correlacionable por sesión.
 */
class AuditTokenFallbackTest {

    private fun cfg(token: String, params: String = "<Configuration/>"): ModuleConfig =
        ModuleConfig(
            token = token,
            parametersXml = params,
            shopXml = ""
        )

    @Test
    fun `config inicializada usa el token de INITIALIZE`() {
        val config = cfg(token = "uuid-de-inicialize")
        assertEquals("uuid-de-inicialize", deriveAuditToken(config, "icg.android.start"))
    }

    @Test
    fun `config sin inicializar usa el caller como token derivado`() {
        // token vacío → isInitialized = false
        val config = cfg(token = "")
        assertEquals("auto-icg.android.start", deriveAuditToken(config, "icg.android.start"))
    }

    @Test
    fun `caller null cae a auto-unknown sin crashear`() {
        val config = cfg(token = "")
        val token = deriveAuditToken(config, null)
        assertEquals("auto-unknown", token)
        assertTrue("el formato auto- es estable", token.startsWith("auto-"))
    }

    @Test
    fun `downgrade - si INITIALIZE vuelve, recuperamos el token original`() {
        // Sin INITIALIZE: token derivado del caller.
        val tokenAuto = deriveAuditToken(cfg(token = ""), "icg.android.start")
        assertEquals("auto-icg.android.start", tokenAuto)

        // HioPosCloud envía INITIALIZE, configura el módulo y dispara la
        // siguiente action. Volvemos a token de INITIALIZE.
        val tokenInicializado = deriveAuditToken(
            cfg(token = "uuid-real"),
            "icg.android.start"
        )
        assertEquals("uuid-real", tokenInicializado)
    }
}
