package com.permoda.facturacion.entry

import android.app.Activity
import android.os.Bundle
import com.permoda.facturacion.PermodaViewApp
import com.permoda.facturacion.R

/**
 * INITIALIZE — Prepara a PermodaView para funcionar.
 *
 * Extras que HioPosCloud envía:
 *  - Token       (String, obligatorio): UUID único, persistido para auditoría.
 *  - Parameters  (String XML, obligatorio): configuración guardada en CloudLicense.
 *  - Shop        (String XML, opcional):   info de la tienda.
 *  - License     (String, opcional):       tipo de licencia (Basic, Retail, …).
 *
 * Responde con `RESULT_OK` (status=OK) o `RESULT_CANCELED` + `ErrorMessage`.
 */
class InitializeActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            assertFromTrustedCaller()

            val token = intent.getStringExtra(ApiActions.EXTRA_TOKEN).orEmpty()
            val parameters = intent.getStringExtra(ApiActions.EXTRA_PARAMETERS).orEmpty()
            val shop = intent.getStringExtra(ApiActions.EXTRA_SHOP).orEmpty()
            val license = intent.getStringExtra(ApiActions.EXTRA_LICENSE).orEmpty()

            if (token.isBlank() || parameters.isBlank()) {
                finishCancelled(getString(R.string.error_invalid_intent))
                return
            }

            val store = (application as PermodaViewApp).configStore
            store.setToken(token)
            store.setParameters(parameters)
            if (shop.isNotBlank()) store.setShop(shop)
            // Se guarda aunque hoy no se use: si mañana hay que discriminar por
            // tipo de licencia, el dato ya está y no hace falta re-INITIALIZE.
            if (license.isNotBlank()) store.setLicense(license)

            // HU-HIOPOS · `action` es el dato clave: con la sonda multi-namespace
            // activa, es lo que revela con qué <apk_name> nos llamó HioPosCloud.
            android.util.Log.i(
                "PvHandshake",
                "INITIALIZE ← caller=${callingPackage ?: "desconocido"} " +
                    "action=${intent.action} esperada=${ApiActions.INITIALIZE} " +
                    "tokenLen=${token.length} paramsLen=${parameters.length} " +
                    "shop=${shop.isNotBlank()} license='$license'"
            )
            finishOk()
        } catch (t: Throwable) {
            finishCancelled("init_error: ${t.message}")
        }
    }
}
