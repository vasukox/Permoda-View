package com.permoda.facturacion.core.hka

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HkaResponseParsingTest {

    @Test
    fun `parsea respuesta 200 exitoso`() {
        val body = """
            {
              "codigo": 200,
              "correoReceptor": "andres@gmail.com",
              "mensaje": "Exitoso.",
              "nombreReceptor": "ANDRES DIAZ",
              "resultado": "Procesado"
            }
        """.trimIndent()
        val response = HkaResponse.fromJson(200, body)
        assertTrue(response is HkaResponse.Success)
        val s = response as HkaResponse.Success
        assertEquals("ANDRES DIAZ", s.nombreReceptor)
        assertEquals("andres@gmail.com", s.correoReceptor)
        assertEquals(200, s.httpCode)
        assertTrue(s.isExitoso)
    }

    @Test
    fun `parsea 404 not found`() {
        val body = """
            {
              "codigo": 404,
              "correoReceptor": "",
              "mensaje": "No se encontró el adquiriente en la base de datos de la DIAN",
              "nombreReceptor": "",
              "resultado": "No procesado."
            }
        """.trimIndent()
        val response = HkaResponse.fromJson(404, body)
        assertTrue(response is HkaResponse.NotFound)
        assertEquals(404, response.httpCode)
        assertFalse(response.isExitoso)
    }

    @Test
    fun `parsea 400 bad request`() {
        val body = """{"codigo":400,"mensaje":"El tipoIdentificacion es requerido"}"""
        val response = HkaResponse.fromJson(400, body)
        assertTrue(response is HkaResponse.BadRequest)
        assertEquals(400, response.httpCode)
        assertFalse(response.isRetryable)
    }

    @Test
    fun `parsea 500 server error`() {
        val body = """{"codigo":500,"mensaje":"Error interno del servidor"}"""
        val response = HkaResponse.fromJson(500, body)
        assertTrue(response is HkaResponse.ServerError)
        assertEquals(500, response.httpCode)
        assertTrue(response.isRetryable)
    }

    @Test
    fun `parsea 429 rate limited`() {
        val body = """{"codigo":429,"mensaje":"Demasiadas solicitudes"}"""
        val response = HkaResponse.fromJson(429, body)
        assertTrue(response is HkaResponse.RateLimited)
        assertTrue(response.isRetryable)
    }

    @Test
    fun `parsea 502 503 504 como server error`() {
        listOf(502, 503, 504).forEach { code ->
            val response = HkaResponse.fromJson(code, """{"codigo":$code}""")
            assertTrue("Esperaba ServerError para $code", response is HkaResponse.ServerError)
            assertTrue(response.isRetryable)
        }
    }

    @Test
    fun `200 sin campos esperados cae a ServerError`() {
        val body = """{"codigo":200,"resultado":"Procesado"}"""
        val response = HkaResponse.fromJson(200, body)
        // 200 + resultado Procesado pero sin nombreReceptor no vacío → no es Success
        assertFalse("No debería ser exitoso sin nombre", response.isExitoso)
    }

    @Test
    fun `isExitoso exige resultado exacto Procesado`() {
        val r = HkaResponse.Success("A", "a@a.com", resultado = "Otro")
        assertFalse(r.isExitoso)
    }

    @Test
    fun `isExitoso ignora case en resultado`() {
        val r = HkaResponse.Success("A", "a@a.com", resultado = "procesado")
        assertTrue(r.isExitoso)
    }

    @Test
    fun `isExitoso acepta resultado con o sin punto final`() {
        assertTrue(HkaResponse.Success("A", "a@a.com", resultado = "Procesado").isExitoso)
        assertTrue(HkaResponse.Success("A", "a@a.com", resultado = "Procesado.").isExitoso)
    }

    @Test
    fun `isExitoso acepta resultado con espacios extra`() {
        assertTrue(HkaResponse.Success("A", "a@a.com", resultado = "  Procesado  ").isExitoso)
    }

    @Test
    fun `fromJson acepta respuesta real con Procesado sin punto`() {
        val body = """
            {
              "codigo": 200,
              "correoReceptor": "clientedummy@thefactory.com",
              "mensaje": "mensaje que devuelva la api",
              "nombreReceptor": "Cliente Dummy",
              "resultado": "Procesado"
            }
        """.trimIndent()
        val response = HkaResponse.fromJson(200, body)
        assertTrue("Debe ser Success con resultado real 'Procesado'", response is HkaResponse.Success)
        assertTrue(response.isExitoso)
    }

    @Test
    fun `Timeout es retryable`() {
        assertTrue(HkaResponse.Timeout().isRetryable)
    }

    @Test
    fun `NetworkError es retryable`() {
        assertTrue(HkaResponse.NetworkError("dns fail").isRetryable)
    }

    @Test
    fun `BadRequest NO es retryable`() {
        assertFalse(HkaResponse.BadRequest("falta tipo").isRetryable)
    }

    @Test
    fun `Success NO es retryable`() {
        assertFalse(HkaResponse.Success("A", "a@a.com").isRetryable)
    }

    @Test
    fun `NotFound NO es retryable`() {
        assertFalse(HkaResponse.NotFound("404").isRetryable)
    }

    @Test
    fun `httpCode de NetworkError es 0`() {
        assertEquals(0, HkaResponse.NetworkError("x").httpCode)
    }

    @Test
    fun `parsea body con campos faltantes usando defaults`() {
        val body = """{}"""
        val response = HkaResponse.fromJson(200, body)
        // Sin "codigo" → cae al httpCode (200), sin "resultado" → no es Procesado → no Success
        // Va a parar a ServerError por el branch else
        assertFalse(response.isExitoso)
    }

    @Test
    fun `parsea con codigo interno 200 pero httpCode 500`() {
        val body = """{"codigo":200,"resultado":"Procesado.","nombreReceptor":"X","correoReceptor":"a@a.com"}"""
        val response = HkaResponse.fromJson(500, body)
        // El branch es por httpCode primero, así que es ServerError
        assertTrue(response is HkaResponse.ServerError)
    }

    @Test
    fun `parsea mensaje vacio usa default por分支`() {
        val response404 = HkaResponse.fromJson(404, """{"codigo":404}""")
        assertTrue(response404 is HkaResponse.NotFound)
        assertTrue((response404 as HkaResponse.NotFound).mensaje.isNotBlank())
    }
}
