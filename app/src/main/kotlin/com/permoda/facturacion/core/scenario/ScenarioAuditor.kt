package com.permoda.facturacion.core.scenario

import android.content.Context
import com.permoda.facturacion.core.audit.AuditClient

/**
 * Auditoría del escenario con el que se resolvió cada selección de cliente, y
 * de la decisión final del cliente (aceptar / rechazar).
 *
 * Nombres de acción del contrato acordado con ICG:
 *  - `cliente_existente`         → ya estaba en HioPosCloud (escenario 1)
 *  - `cliente_creado_con_dian`   → nuevo, datos de la DIAN (escenario 2)
 *  - `cliente_creado_sin_dian`   → nuevo, captura manual (escenario 3)
 *  - `cliente_consumidor_final`  → genérico 222222222222 (escenario 4)
 *  - `cliente_acepta`            → pulsó Aceptar
 *  - `cliente_rechaza`           → pulsó No acepto
 *
 * Existe para poder responder desde operaciones "cuántas ventas van como
 * Consumidor Final" o "con qué frecuencia la DIAN no responde y toca escribir
 * a mano", sin inferirlo del log.
 */
object ScenarioAuditor {

    const val EXTRA_SCENARIO = "Scenario"

    /**
     * El escenario se refina durante el flujo, así que esto puede emitirse más
     * de una vez por venta (p.ej. `cliente_creado_sin_dian` al abrir y
     * `cliente_consumidor_final` al final). Cada emisión es un hecho con su
     * timestamp; el consumidor se queda con la última.
     */
    fun emitScenario(
        context: Context,
        enabled: Boolean,
        token: String,
        scenario: CustomerScenario,
        numeroIdentificacion: String?,
        documentId: String? = null
    ) {
        val masked = AuditClient.maskDocumento(numeroIdentificacion)
        val comment = when (scenario) {
            CustomerScenario.EDIT ->
                "Cliente $masked ya existía en HioPosCloud (sin consulta DIAN)"
            CustomerScenario.CREATE_FROM_DIAN ->
                "Cliente $masked no estaba en HioPosCloud, datos tomados de la DIAN"
            CustomerScenario.COMPLETE_EXISTING ->
                "Cliente $masked ya existía en HioPosCloud con datos incompletos, se completaron"
            CustomerScenario.CREATE_MANUAL ->
                "Cliente $masked no estaba en HioPosCloud ni en la DIAN, captura manual"
            CustomerScenario.CONSUMIDOR_FINAL ->
                "Venta facturada como Consumidor Final (el cliente no entregó datos)"
        }
        AuditClient.send(
            context = context,
            enabled = enabled,
            token = token,
            action = scenario.auditAction,
            comment = comment,
            documentId = documentId,
            extra = mapOf(EXTRA_SCENARIO to scenario.name)
        )
    }

    /** El cliente pulsó Aceptar. */
    fun emitAcepta(
        context: Context,
        enabled: Boolean,
        token: String,
        scenario: CustomerScenario,
        numeroIdentificacion: String?,
        documentId: String? = null
    ) = AuditClient.send(
        context = context,
        enabled = enabled,
        token = token,
        action = "cliente_acepta",
        comment = "Cliente ${AuditClient.maskDocumento(numeroIdentificacion)} " +
            "aceptó sus datos (escenario ${scenario.name})",
        documentId = documentId,
        extra = mapOf(EXTRA_SCENARIO to scenario.name)
    )

    /** El cliente pulsó No acepto. */
    fun emitRechaza(
        context: Context,
        enabled: Boolean,
        token: String,
        reason: String,
        numeroIdentificacion: String?,
        documentId: String? = null
    ) = AuditClient.send(
        context = context,
        enabled = enabled,
        token = token,
        action = "cliente_rechaza",
        comment = "Cliente ${AuditClient.maskDocumento(numeroIdentificacion)} " +
            "rechazó la confirmación: $reason",
        documentId = documentId
    )
}
