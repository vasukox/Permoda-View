package com.permoda.facturacion.core.audit

import android.content.Context
import android.content.Intent
import android.util.Log

/** Auditoría dirigida al paquete del POS; identificadores enmascarados y sin datos de contacto. */
object AuditClient {

    private const val TAG = "AuditClient"

    /** Action del contrato de ICG. */
    const val ACTION_AUDIT = "icg.actions.externalApi.AUDIT"

    const val EXTRA_ACTION = "Action"
    const val EXTRA_COMMENT = "Comment"
    const val EXTRA_TOKEN = "Token"
    const val EXTRA_DOCUMENT_ID = "DocumentId"

    /** Topes del contrato. Se truncan aquí para no depender de cada caller. */
    const val MAX_ACTION_LENGTH = 50
    const val MAX_COMMENT_LENGTH = 1050

    /**
     * @param token UUID recibido en INITIALIZE. Se envía siempre; si llega
     *   vacío se omite el extra en vez de mandar basura.
     * @param documentId id del documento en curso, si el caller lo conoce.
     * @param extra pares adicionales propios de PermodaView (HttpCode, OptIn,
     *   Scenario…). No forman parte del contrato de ICG pero son inofensivos
     *   para un receiver que los ignore.
     */
    fun send(
        context: Context,
        enabled: Boolean,
        token: String,
        action: String,
        comment: String,
        documentId: String? = null,
        extra: Map<String, Any?> = emptyMap()
    ) {
        if (!enabled) return

        val intent = Intent(ACTION_AUDIT).apply {
            // Restringido al POS real: otras aplicaciones no reciben token ni eventos.
            setPackage(com.permoda.facturacion.entry.ApiActions.TRUSTED_HIOPOS_PACKAGE)
            putExtra(EXTRA_ACTION, action.take(MAX_ACTION_LENGTH))
            putExtra(EXTRA_COMMENT, truncateComment(comment))
            if (token.isNotBlank()) putExtra(EXTRA_TOKEN, token)
            documentId?.takeIf { it.isNotBlank() }?.let { putExtra(EXTRA_DOCUMENT_ID, it) }
            extra.forEach { (key, value) ->
                when (value) {
                    null -> Unit
                    is Boolean -> putExtra(key, value)
                    is Int -> putExtra(key, value)
                    is Long -> putExtra(key, value)
                    else -> putExtra(key, value.toString())
                }
            }
        }
        try {
            context.sendBroadcast(intent)
        } catch (t: Throwable) {
            Log.w(TAG, "No se pudo emitir auditoría '$action': ${t.message}")
        }
    }

    /**
     * Trunca dejando marca visible. El comment interpola mensajes de la DIAN,
     * que son texto ajeno sin cota: sin esto un 5xx verboso podría pasarse del
     * límite del contrato.
     */
    private fun truncateComment(comment: String): String =
        if (comment.length <= MAX_COMMENT_LENGTH) comment
        else comment.take(MAX_COMMENT_LENGTH - 1) + "…"

    /**
     * Enmascara un documento para auditoría: deja los últimos 4 dígitos.
     * Cumple el principio de Habeas Data.
     */
    fun maskDocumento(doc: String?): String {
        val clean = doc?.trim().orEmpty()
        if (clean.isBlank()) return "sin-documento"
        if (clean.length <= 4) return "***"
        return "*".repeat(clean.length - 4) + clean.takeLast(4)
    }
}
