package com.permoda.facturacion.entry

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import com.permoda.facturacion.core.manage.ManageCustomerTransport
import com.permoda.facturacion.core.modify.ModifyDocumentXml
import com.permoda.facturacion.core.totalize.TotalizeGate
import java.io.File

/**
 * MODIFY_DOCUMENT: valida los datos del cliente antes de las formas de pago.
 *
 * Sin UI propia: el mensaje de bloqueo lo pinta HioPos en su diálogo. La
 * política de fallos vive en [TotalizeGate]: sólo se bloquea por datos del
 * cliente; cualquier error técnico deja seguir la venta y queda en el log.
 */
class TotalizeValidationActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            assertFromTrustedCaller()
        } catch (e: SecurityException) {
            finishCancelled("Emisor no autorizado")
            return
        }
        val trigger = intent.getStringExtra(ApiActions.EXTRA_TRIGGER_EVENT)
        logIncomingExtras(trigger)

        // El documento puede llegar por URI: se lee fuera del hilo principal
        // para no congelar la caja si el provider tarda.
        Thread({
            val decision = try {
                val xml = if (trigger in TotalizeGate.TOTALIZE_TRIGGERS) readDocumentXml() else null
                TotalizeGate.decide(trigger, xml)
            } catch (e: Exception) {
                // Nunca el mensaje de la excepción: puede citar el XML.
                TotalizeGate.Decision.Allow("error leyendo el documento (${e.javaClass.simpleName}); se deja seguir")
            }
            runOnUiThread { respond(decision) }
        }, "PvTotalize").start()
    }

    private fun respond(decision: TotalizeGate.Decision) {
        if (isFinishing) return
        when (decision) {
            is TotalizeGate.Decision.Allow -> {
                Log.i(TAG, "TOTALIZE → continuar: ${decision.reason}")
                setResult(RESULT_OK, Intent().apply {
                    action = intent.action
                    putExtra(ApiActions.RESULT_STATUS, ApiActions.Status.OK)
                    putExtra(ApiActions.RESULT_MODIFY_DOCUMENT_RESULT, ModifyDocumentXml.EMPTY_RESULT)
                })
                finish()
            }
            is TotalizeGate.Decision.Block -> {
                // Sólo los nombres de los campos: los valores son datos personales.
                Log.w(TAG, "TOTALIZE → bloqueado: campos=${decision.fields.joinToString { it.name }}")
                finishCancelled(decision.message)
            }
        }
    }

    /** Orden del contrato: el XML en línea, luego la URI (Android 10+), luego la ruta. */
    private fun readDocumentXml(): String? {
        intent.getStringExtra(ApiActions.EXTRA_DOCUMENT)?.takeIf { it.isNotBlank() }?.let {
            require(it.toByteArray(Charsets.UTF_8).size <= ModifyDocumentXml.MAX_BYTES) { "Documento demasiado grande" }
            return it
        }
        @Suppress("DEPRECATION")
        val uri = intent.getParcelableExtra<Uri>(ApiActions.EXTRA_DOCUMENT_URI)
        if (uri != null) {
            return ManageCustomerTransport.read(
                null, ManageCustomerTransport.Resource(uri.scheme, uri.authority), callingPackage,
                ModifyDocumentXml.MAX_BYTES
            ) { contentResolver.openInputStream(uri) }
        }
        val path = intent.getStringExtra(ApiActions.EXTRA_DOCUMENT_PATH) ?: return null
        val file = File(path).canonicalFile
        // Sólo la carpeta de intercambio del POS: la ruta llega en un extra.
        require(file.parentFile?.name == EXCHANGE_DIR) { "Ruta de documento no autorizada" }
        require(file.length() in 1..ModifyDocumentXml.MAX_BYTES.toLong()) { "Documento vacío o demasiado grande" }
        return file.readText(Charsets.UTF_8)
    }

    /** Sólo nombres y tipos de los extras, más el evento y si es factura. */
    private fun logIncomingExtras(trigger: String?) {
        val extras = intent.extras
        @Suppress("DEPRECATION")
        val summary = extras?.keySet()?.sorted()?.joinToString { "$it:${extras.get(it)?.javaClass?.simpleName ?: "null"}" }
        Log.i(
            TAG,
            "MODIFY_DOCUMENT ← caller=${callingPackage ?: "desconocido"} trigger=${trigger ?: "ninguno"} " +
                "isInvoice=${intent.getBooleanExtra(ApiActions.EXTRA_IS_INVOICE, false)} extras=[${summary.orEmpty()}]"
        )
    }

    private companion object {
        const val TAG = "PvTotalize"
        const val EXCHANGE_DIR = "APIExternalTraffic"
    }
}
