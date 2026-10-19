package com.permoda.facturacion.core.hka

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * La DIAN puede responder `200 Procesado` sin `nombreReceptor`.
 *
 * Antes ese caso no cumplía ninguna rama y caía en el `else` final como
 * `ServerError("Respuesta inesperada (200)")`, lo que en modo CONSERVADOR
 * terminaba bloqueando la venta. Es un "no encontrado": la DIAN contestó
 * bien y simplemente no tiene el dato.
 */
class HkaResponseBlankNameTest {

    private fun body(nombre: String, correo: String = "") = """
        {"codigo":200,"mensaje":"Exitoso.","resultado":"Procesado",
         "nombreReceptor":"$nombre","correoReceptor":"$correo"}
    """.trimIndent()

    @Test
    fun `200 con nombre vacio es NotFound`() {
        val response = HkaResponse.fromJson(200, body(""))
        assertTrue("esperaba NotFound, fue $response", response is HkaResponse.NotFound)
        assertEquals(200, response.httpCode)
    }

    @Test
    fun `200 con nombre vacio no es exitoso`() {
        assertEquals(false, HkaResponse.fromJson(200, body("")).isExitoso)
    }

    @Test
    fun `200 con nombre vacio no es reintentable`() {
        // No tiene sentido reintentar: la DIAN ya contestó que no lo tiene.
        assertEquals(false, HkaResponse.fromJson(200, body("")).isRetryable)
    }

    @Test
    fun `200 con nombre lleno sigue siendo Success`() {
        val response = HkaResponse.fromJson(200, body("JUAN PEREZ", "juan@mail.com"))
        assertTrue(response is HkaResponse.Success)
        assertEquals("JUAN PEREZ", (response as HkaResponse.Success).nombreReceptor)
        assertTrue(response.isExitoso)
    }

    @Test
    fun `un 200 sin nombre lleva a captura manual y no a bloqueo`() {
        val response = HkaResponse.fromJson(200, body(""))
        val merge = CustomerMerger.merge(
            local = com.permoda.facturacion.core.model.CustomerData(customerName = ""),
            hka = null,
            fallbackMode = HkaConfig.FallbackMode.CONSERVADOR,
            hkaError = response
        )
        assertEquals(MergeSource.DIAN_NOT_REGISTERED, merge.source)
        assertEquals(DianStatus.NOT_REGISTERED, merge.dianStatus)
    }
}
