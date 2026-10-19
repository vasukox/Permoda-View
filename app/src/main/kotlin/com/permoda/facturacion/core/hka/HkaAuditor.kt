package com.permoda.facturacion.core.hka

import android.content.Context
import com.permoda.facturacion.core.audit.AuditClient

/**
 * Auditoría de cada consulta a la DIAN (The Factory HKA).
 *
 * Los nombres de acción siguen el contrato acordado con ICG:
 *  - `hka_consulta_ok`      → la DIAN devolvió datos
 *  - `hka_consulta_404`     → la DIAN no tiene el documento
 *  - `hka_consulta_error`   → timeout, 5xx, sin red, JSON roto
 *  - `hka_consulta_skipped` → no se consultó (deshabilitada o sin documento)
 *
 * El envío real lo hace [AuditClient], que pone el `Token` y respeta los topes
 * de longitud del contrato.
 */
object HkaAuditor {

    fun emitConsulta(
        context: Context,
        enabled: Boolean,
        token: String,
        tipoIdentificacion: String,
        numeroIdentificacionMasked: String,
        response: HkaResponse?,
        mergeSource: MergeSource? = null,
        documentId: String? = null
    ) {
        val (action, comment) = describe(
            tipoIdentificacion, numeroIdentificacionMasked, response, mergeSource
        )
        AuditClient.send(
            context = context,
            enabled = enabled,
            token = token,
            action = action,
            comment = comment,
            documentId = documentId,
            extra = mapOf(
                "HttpCode" to (response?.httpCode ?: 0),
                "Source" to mergeSource?.name
            )
        )
    }

    /**
     * `internal` y no `private` a propósito: el test lo invoca directamente.
     * Antes era privado y el test **replicaba** este `when` en su propio
     * helper, así que verificaba su propia copia y no habría detectado un
     * cambio de nombres aquí.
     */
    internal fun describe(
        tipo: String,
        numeroMasked: String,
        response: HkaResponse?,
        source: MergeSource?
    ): Pair<String, String> {
        val action = when (response) {
            is HkaResponse.Success  -> "hka_consulta_ok"
            is HkaResponse.NotFound -> "hka_consulta_404"
            null                    -> "hka_consulta_skipped"
            else                    -> "hka_consulta_error"
        }
        val comment = when (response) {
            is HkaResponse.Success ->
                "Cliente $numeroMasked ($tipo) validado en DIAN" +
                    (source?.let { " [merge=$it]" } ?: "")
            is HkaResponse.NotFound ->
                "Cliente $numeroMasked ($tipo) no encontrado en DIAN"
            is HkaResponse.Timeout ->
                "Error DIAN: timeout consultando $numeroMasked ($tipo)"
            is HkaResponse.ServerError ->
                "Error DIAN ${response.httpCode} para $numeroMasked"
            is HkaResponse.BadRequest ->
                "Error DIAN 400 para $numeroMasked"
            is HkaResponse.RateLimited ->
                "Error DIAN 429 (rate limit) para $numeroMasked"
            is HkaResponse.NetworkError ->
                "Error DIAN: sin conexión consultando $numeroMasked"
            null ->
                "DIAN no consultada para $numeroMasked (HKA_ENABLED=false o falta tipo/documento)"
        }
        return action to comment
    }

    /** Delegado a [AuditClient] para no duplicar la regla de enmascarado. */
    fun maskDocumento(doc: String): String = AuditClient.maskDocumento(doc)
}
