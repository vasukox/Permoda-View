package com.permoda.facturacion.core.hka

import com.permoda.facturacion.core.model.CustomerData
import com.permoda.facturacion.core.validation.CustomerValidator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Respuestas **reales** capturadas del POS en producción contra
 * `emision21-api.thefactoryhka.com.co`.
 *
 * Los cuerpos son copia literal del logcat. Sirven para que un cambio en el
 * parser no rompa el comportamiento verificado en terminal.
 */
class HkaRealResponsesTest {

    /** Documento 95656565 · cliente existente sin correo registrado. */
    private val exitosoSinCorreo = """
        {"codigo":200,"correoReceptor":"sincorreo@sincorreo.com","mensaje":"Exitoso.",
         "nombreReceptor":"CARLOS EDUARDO TARAZONA JAIME","resultado":"Procesado."}
    """.trimIndent()

    /** Documento 6586686565 · inexistente. HTTP 200 con codigo 500 en el cuerpo. */
    private val errorEnCuerpo200 = """
        {"codigo":500,"correoReceptor":null,
         "mensaje":"No fue posible obtener los datos desde la DIAN. Por favor, verifique que el identificador sea correcto o intente de nuevo más tarde.",
         "nombreReceptor":null,
         "resultado":"Error al consultar la informacion. Verifique e intente nuevamente"}
    """.trimIndent()

    // ------------------------------------------------------------------
    // El correo de relleno de la DIAN
    // ------------------------------------------------------------------

    @Test
    fun `la respuesta real exitosa se interpreta como Success`() {
        val response = HkaResponse.fromJson(200, exitosoSinCorreo)
        assertTrue(response is HkaResponse.Success)
        assertEquals("CARLOS EDUARDO TARAZONA JAIME", (response as HkaResponse.Success).nombreReceptor)
        assertTrue(response.isExitoso)
    }

    @Test
    fun `sincorreo arroba sincorreo se descarta como si no hubiera correo`() {
        // Si se aceptara, pasaría la validación de campos obligatorios y la
        // factura electrónica se enviaría a un buzón inexistente.
        val response = HkaResponse.fromJson(200, exitosoSinCorreo) as HkaResponse.Success
        assertEquals("", response.correoReceptor)
    }

    @Test
    fun `un correo real si se conserva`() {
        val body = """
            {"codigo":200,"correoReceptor":"carlos.tarazona@gmail.com","mensaje":"Exitoso.",
             "nombreReceptor":"CARLOS EDUARDO TARAZONA JAIME","resultado":"Procesado."}
        """.trimIndent()
        val response = HkaResponse.fromJson(200, body) as HkaResponse.Success
        assertEquals("carlos.tarazona@gmail.com", response.correoReceptor)
    }

    @Test
    fun `los rellenos conocidos se detectan sin importar mayusculas ni espacios`() {
        listOf(
            "sincorreo@sincorreo.com",
            "  SinCorreo@SinCorreo.COM  ",
            "nocorreo@nocorreo.com",
            "na@na.com"
        ).forEach {
            assertTrue("'$it' debería ser relleno", HkaResponse.isPlaceholderEmail(it))
        }
    }

    @Test
    fun `un correo normal no se confunde con relleno`() {
        listOf("juan@gmail.com", "carlos.tarazona@permoda.com.co", null, "", "   ")
            .forEach {
                assertFalse("'$it' no es relleno", HkaResponse.isPlaceholderEmail(it))
            }
    }

    @Test
    fun `el correo de relleno NO alcanza para pasar la validacion`() {
        // El punto del arreglo: con el relleno descartado, el flujo pide el
        // correo igual que si la DIAN no hubiera devuelto nada.
        val response = HkaResponse.fromJson(200, exitosoSinCorreo) as HkaResponse.Success
        val merge = CustomerMerger.merge(
            local = CustomerData(
                customerName = "",
                fiscalDocumentType = "13",
                fiscalId = "95656565",
                email = ""
            ),
            hka = response,
            fallbackMode = HkaConfig.FallbackMode.HIBRIDO
        )
        assertEquals("CARLOS EDUARDO TARAZONA JAIME", merge.customer.customerName)
        val validacion = CustomerValidator.validate(merge.customer)
        assertFalse("no debería poder facturarse sin correo real", validacion.isValid)
        assertTrue(validacion.has(CustomerValidator.Field.EMAIL))
    }

    // ------------------------------------------------------------------
    // HTTP 200 con codigo de error en el cuerpo
    // ------------------------------------------------------------------

    @Test
    fun `un 200 con codigo 500 se interpreta como ServerError`() {
        val response = HkaResponse.fromJson(200, errorEnCuerpo200)
        assertTrue("fue $response", response is HkaResponse.ServerError)
        assertTrue(
            (response as HkaResponse.ServerError).mensaje.contains("verifique que el identificador")
        )
        assertFalse(response.isExitoso)
    }

    @Test
    fun `ese caso degrada a captura manual y no bloquea`() {
        val response = HkaResponse.fromJson(200, errorEnCuerpo200)
        val merge = CustomerMerger.merge(
            local = CustomerData(customerName = "", fiscalId = "6586686565"),
            hka = null,
            fallbackMode = HkaConfig.FallbackMode.CONSERVADOR,
            hkaError = response
        )
        assertEquals(MergeSource.LOCAL_FALLBACK_WARNING, merge.source)
        assertEquals(DianStatus.UNVERIFIED, merge.dianStatus)
    }
}
