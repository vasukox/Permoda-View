package com.permoda.facturacion.entry

import android.app.Activity
import android.os.Bundle

/**
 * SHOW_SETUP_SCREEN — Pantalla de configuración del módulo en el terminal.
 *
 * HU-HIOPOS · Faltaba. El manifest de referencia que ICG entregó en la reunión
 * la declara, y aunque el contrato no la marca como obligatoria, un módulo de
 * documento que no la resuelve deja al POS sin nada que abrir si el operador
 * entra a configurarlo.
 *
 * PermodaView **no tiene parámetros configurables en el terminal**: todo se
 * ajusta desde CloudLicense (`HKA_*`, `KOAJ_*`) y llega en el XML de
 * `Parameters` de INITIALIZE. Ese es el diseño y no se cambia acá: una pantalla
 * local sería una segunda fuente de verdad para la misma configuración, y la de
 * CloudLicense se aplica a toda la flota sin liberar versión.
 *
 * Así que esta activity responde `RESULT_OK` sin UI. No es un stub olvidado: es
 * la respuesta correcta para un módulo cuya configuración es remota. El log deja
 * constancia de que el operador la invocó, con la tienda y el terminal.
 *
 * Extras que HioPosCloud envía (contrato HPCL.025, pág. 9):
 *  - Parameters (String XML), ShopId, PosId, isReadOnly, LanguageISO.
 */
class SetupScreenActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val shopId = intent.getStringExtra("ShopId").orEmpty()
        val posId = intent.getStringExtra("PosId").orEmpty()
        val readOnly = intent.getBooleanExtra("isReadOnly", false)

        android.util.Log.i(
            "PvHandshake",
            "SHOW_SETUP_SCREEN ← caller=${callingPackage ?: "desconocido"} " +
                "action=${intent.action} esperada=${ApiActions.SHOW_SETUP_SCREEN} " +
                "shop='$shopId' pos='$posId' readOnly=$readOnly " +
                "→ sin UI: PermodaView se configura desde CloudLicense"
        )

        finishOk()
    }
}
