package com.permoda.facturacion.core.audit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Blinda el contrato de auditoría con ICG. Los tres fallos que estos tests
 * previenen ya ocurrieron:
 *
 *  1. Action equivocado (`com.permoda.facturacion.api.AUDIT`).
 *  2. `setPackage(packageName)`, que impedía que HioPosCloud recibiera nada.
 *  3. Falta del `Token`.
 *
 * El envío en sí necesita un Context de Android, así que aquí se verifica lo
 * que es lógica pura: constantes del contrato, topes y enmascarado.
 */
class AuditClientTest {

    @Test
    fun `el action es el del contrato de ICG`() {
        assertEquals("icg.actions.externalApi.AUDIT", AuditClient.ACTION_AUDIT)
    }

    @Test
    fun `los nombres de los extras son los del contrato`() {
        assertEquals("Action", AuditClient.EXTRA_ACTION)
        assertEquals("Comment", AuditClient.EXTRA_COMMENT)
        assertEquals("Token", AuditClient.EXTRA_TOKEN)
        assertEquals("DocumentId", AuditClient.EXTRA_DOCUMENT_ID)
    }

    @Test
    fun `los topes son los del contrato`() {
        assertEquals(50, AuditClient.MAX_ACTION_LENGTH)
        assertEquals(1050, AuditClient.MAX_COMMENT_LENGTH)
    }

    // ------------------------------------------------------------------
    // Enmascarado (Habeas Data)
    // ------------------------------------------------------------------

    @Test
    fun `enmascara dejando los ultimos cuatro digitos`() {
        assertEquals("******6580", AuditClient.maskDocumento("1011086580"))
        assertEquals("********2222", AuditClient.maskDocumento("222222222222"))
    }

    @Test
    fun `documentos muy cortos se ocultan completos`() {
        assertEquals("***", AuditClient.maskDocumento("1234"))
        assertEquals("***", AuditClient.maskDocumento("12"))
    }

    @Test
    fun `sin documento no revienta`() {
        assertEquals("sin-documento", AuditClient.maskDocumento(null))
        assertEquals("sin-documento", AuditClient.maskDocumento(""))
        assertEquals("sin-documento", AuditClient.maskDocumento("   "))
    }

    @Test
    fun `enmascarar nunca deja el documento completo visible`() {
        val doc = "1011086580"
        val masked = AuditClient.maskDocumento(doc)
        assertTrue("el documento no debe aparecer en claro", !masked.contains(doc))
        assertTrue(masked.endsWith(doc.takeLast(4)))
    }
}
