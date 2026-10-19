package com.permoda.facturacion.core.loyalty

import android.content.Context
import com.permoda.facturacion.core.audit.AuditClient

/**
 * Auditoría de la decisión sobre **Koaj Lovers**.
 *
 * Acciones (propias de PermodaView, no están en el contrato base de ICG):
 *  - `koaj_opt_in`   → el cliente se unió
 *  - `koaj_declined` → declinó explícitamente
 *  - `koaj_timeout`  → venció el auto-avance (sólo si operaciones lo activó;
 *                      por defecto la pantalla espera indefinidamente)
 *
 * Nunca se envía el documento en claro: la pertenencia a un programa de
 * fidelización es un dato personal y aplica Habeas Data igual que la consulta
 * a la DIAN.
 */
object LoyaltyAuditor {

    const val EXTRA_OPT_IN = "OptIn"

    fun emit(
        context: Context,
        enabled: Boolean,
        token: String,
        outcome: LoyaltyOutcome,
        numeroIdentificacion: String?,
        documentId: String? = null
    ) {
        val masked = AuditClient.maskDocumento(numeroIdentificacion)

        val action = when (outcome) {
            LoyaltyOutcome.ACCEPTED -> "koaj_opt_in"
            LoyaltyOutcome.DECLINED -> "koaj_declined"
            LoyaltyOutcome.TIMEOUT  -> "koaj_timeout"
        }
        val comment = when (outcome) {
            LoyaltyOutcome.ACCEPTED -> "Cliente $masked aceptó unirse a Koaj Lovers"
            LoyaltyOutcome.DECLINED -> "Cliente $masked declinó Koaj Lovers"
            LoyaltyOutcome.TIMEOUT  -> "Cliente $masked no respondió a Koaj Lovers (auto-avance)"
        }

        AuditClient.send(
            context = context,
            enabled = enabled,
            token = token,
            action = action,
            comment = comment,
            documentId = documentId,
            extra = mapOf(EXTRA_OPT_IN to outcome.optIn)
        )
    }
}
