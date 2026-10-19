package com.permoda.facturacion.core.hka

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifica los nombres de acción del contrato con ICG y el enmascarado.
 *
 * Llama a `HkaAuditor.describe` de verdad (es `internal`). La versión anterior
 * de este test replicaba el `when` de producción en un helper local, así que
 * validaba su propia copia: cuando los nombres cambiaron, no se enteró.
 */
class HkaAuditorTest {

    private fun describeFor(
        tipo: String = "13",
        numero: String = "12345678",
        response: HkaResponse?,
        source: MergeSource? = null
    ): Pair<String, String> =
        HkaAuditor.describe(tipo, HkaAuditor.maskDocumento(numero), response, source)

    // ------------------------------------------------------------------
    // Enmascarado
    // ------------------------------------------------------------------

    @Test
    fun `maskDocumento deja solo los ultimos 4 digitos`() {
        assertEquals("****5678", HkaAuditor.maskDocumento("12345678"))
    }

    @Test
    fun `maskDocumento con 4 digitos o menos oculta todo`() {
        assertEquals("***", HkaAuditor.maskDocumento("1234"))
        assertEquals("***", HkaAuditor.maskDocumento("123"))
        assertEquals("***", HkaAuditor.maskDocumento("12"))
    }

    @Test
    fun `maskDocumento sin documento lo dice en texto`() {
        // "sin-documento" en vez de "***": en un comentario de auditoría
        // distingue "documento oculto" de "no había documento".
        assertEquals("sin-documento", HkaAuditor.maskDocumento(""))
    }

    @Test
    fun `maskDocumento con documento largo enmascara correctamente`() {
        val masked = HkaAuditor.maskDocumento("900123456789")
        assertEquals("********6789", masked)
        assertTrue(masked.endsWith("6789"))
    }

    // ------------------------------------------------------------------
    // Nombres de acción del contrato
    // ------------------------------------------------------------------

    @Test
    fun `Success genera hka_consulta_ok`() {
        val (action, comment) = describeFor(response = HkaResponse.Success("JUAN", "j@j.com"))
        assertEquals("hka_consulta_ok", action)
        assertTrue(!comment.contains("JUAN") && !comment.contains("j@j.com"))
    }

    @Test
    fun `NotFound genera hka_consulta_404`() {
        val (action, comment) = describeFor(response = HkaResponse.NotFound("404"))
        assertEquals("hka_consulta_404", action)
        assertTrue(comment.contains("no encontrado"))
    }

    @Test
    fun `todos los fallos de red y servidor generan hka_consulta_error`() {
        listOf(
            HkaResponse.Timeout(),
            HkaResponse.ServerError("boom", 500),
            HkaResponse.NetworkError("DNS fail"),
            HkaResponse.RateLimited(),
            HkaResponse.BadRequest("falta tipo", 400, 400)
        ).forEach { response ->
            val (action, _) = describeFor(response = response)
            assertEquals("$response debería ser error", "hka_consulta_error", action)
        }
    }

    @Test
    fun `null genera hka_consulta_skipped`() {
        val (action, comment) = describeFor(response = null)
        assertEquals("hka_consulta_skipped", action)
        assertTrue(comment.contains("HKA_ENABLED=false") || comment.contains("falta"))
    }

    // ------------------------------------------------------------------
    // Contenido del comentario
    // ------------------------------------------------------------------

    @Test
    fun `el comentario incluye el MergeSource cuando se pasa`() {
        val (_, comment) = describeFor(
            response = HkaResponse.Success("X", "x@x.com"),
            source = MergeSource.DIAN_OVERRIDE
        )
        assertTrue(comment.contains("DIAN_OVERRIDE"))
    }

    @Test
    fun `el comentario incluye codigo pero no texto ajeno sensible`() {
        val (_, comment) = describeFor(response = HkaResponse.ServerError("boom", 500))
        assertTrue(comment.contains("500"))
        assertTrue(!comment.contains("boom"))
    }

    @Test
    fun `el comentario NUNCA lleva el documento en claro`() {
        val doc = "1011086580"
        listOf(
            HkaResponse.Success("JUAN", "j@j.com"),
            HkaResponse.NotFound("404"),
            HkaResponse.Timeout(),
            HkaResponse.ServerError("boom", 500),
            HkaResponse.NetworkError("DNS"),
            HkaResponse.RateLimited(),
            HkaResponse.BadRequest("x", 400, 400),
            null
        ).forEach { response ->
            val (_, comment) = describeFor(numero = doc, response = response)
            assertTrue(
                "$response filtró el documento en claro: $comment",
                !comment.contains(doc)
            )
        }
    }
}
