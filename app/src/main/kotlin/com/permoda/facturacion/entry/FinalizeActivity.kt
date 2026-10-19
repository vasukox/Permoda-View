package com.permoda.facturacion.entry

import android.app.Activity
import android.os.Bundle
import com.permoda.facturacion.PermodaViewApp

/**
 * FINALIZE — Libera la configuración persistida y cierra PermodaView.
 * HioPosCloud lo invoca cuando se está cerrando.
 */
class FinalizeActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // HU-HIOPOS · `action` es el dato clave: con la sonda multi-namespace
        // activa, es lo que revela con qué <apk_name> nos llamó HioPosCloud.
        android.util.Log.i(
            "PvHandshake",
            "FINALIZE ← caller=${callingPackage ?: "desconocido"} " +
                "action=${intent.action} esperada=${ApiActions.FINALIZE} (limpia config)"
        )
        try {
            assertFromTrustedCaller()
        } catch (e: SecurityException) {
            finishCancelled("Emisor no autorizado")
            return
        }
        (application as PermodaViewApp).configStore.clear()
        finishOk()
    }
}
