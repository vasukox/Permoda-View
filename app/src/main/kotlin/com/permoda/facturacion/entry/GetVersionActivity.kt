package com.permoda.facturacion.entry

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import com.permoda.facturacion.BuildConfig

/**
 * GET_VERSION — Devuelve la versión del módulo.
 *
 * Responde con `RESULT_OK` + extra `Version` = [BuildConfig.ICG_MODULE_VERSION].
 *
 * ## ⚠️ El extra va como INT. No lo pases a String.
 *
 * HioPos lo lee con `getIntExtra`. Verificado en la terminal el 20-08-2026:
 *
 * ```
 * W/Bundle: Key Version expected Integer but value was a java.lang.String.
 *           The default value -1 was returned.
 *   at android.content.Intent.getIntExtra(Intent.java:8880)
 *   at icg.android.external.module.documentAPI.DocumentAPI.checkResult(:316)
 *   at icg.android.start.StartActivity.onActivityResult(:387)
 * ```
 *
 * Acá se enviaba `BuildConfig.VERSION_NAME` —un String— y HioPos recibía **-1**.
 * Comparado contra el 1 registrado en CloudLicense, concluía que el módulo
 * estaba desactualizado y **pedía reinstalarlo en cada arranque de la terminal**.
 * El número era correcto desde el 14-08; el tipo era el problema.
 *
 * Que `ICG_MODULE_VERSION` esté declarado como `int` en `BuildConfig` es lo que
 * impide que la regresión vuelva: no hay forma de pasarlo como String sin que el
 * compilador lo note.
 *
 * Regla vigente del proyecto: VERSION_NAME="1", VERSION_CODE=1 e
 * ICG_MODULE_VERSION=1, sin incrementos automáticos. El extra ICG conserva
 * su tipo Int, aunque la versión visible Android sea una cadena.
 *
 * El Intent de resultado lleva la MISMA action que el recibido (regla ICG).
 */
class GetVersionActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Se captura antes de construir el result porque ahí `intent` queda
        // sombreado por el Intent de respuesta.
        val receivedAction = intent.action
        val result = Intent().apply {
            action = receivedAction
            putExtra(ApiActions.RESULT_STATUS, ApiActions.Status.OK)
            // Int, NO String: ver el aviso del KDoc.
            putExtra(ApiActions.RESULT_VERSION, BuildConfig.ICG_MODULE_VERSION)
        }
        // HU-HIOPOS · `action` es el dato clave: con la sonda multi-namespace
        // activa, es lo que revela con qué <apk_name> nos llamó HioPosCloud.
        android.util.Log.i(
            "PvHandshake",
            "GET_VERSION ← caller=${callingPackage ?: "desconocido"} " +
                "action=$receivedAction esperada=${ApiActions.GET_VERSION} " +
                "→ ${BuildConfig.ICG_MODULE_VERSION} (int)"
        )
        setResult(RESULT_OK, result)
        finish()
    }
}
