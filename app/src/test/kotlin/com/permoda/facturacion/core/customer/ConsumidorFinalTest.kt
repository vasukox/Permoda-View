package com.permoda.facturacion.core.customer

import com.permoda.facturacion.core.hka.TipoDocumentoMapper
import com.permoda.facturacion.core.model.CustomerData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConsumidorFinalTest {

    @Test
    fun `usa los valores de negocio acordados`() {
        val cf = ConsumidorFinal.build()
        assertEquals("Consumidor Final", cf.customerName)
        assertEquals("13", cf.fiscalDocumentType)
        assertEquals("222222222222", cf.fiscalId)
    }

    @Test
    fun `el documento generico tiene doce digitos`() {
        assertEquals(12, ConsumidorFinal.DOCUMENT_ID.length)
        assertTrue(ConsumidorFinal.DOCUMENT_ID.all { it.isDigit() })
    }

    @Test
    fun `el tipo de documento es mapeable a un codigo DIAN valido`() {
        // Si alguien cambia DOCUMENT_TYPE por un valor no soportado, el
        // validador rechazaría al propio Consumidor Final.
        val dian = TipoDocumentoMapper.toDianCode(ConsumidorFinal.DOCUMENT_TYPE)
        assertEquals("13", dian)
        assertTrue(TipoDocumentoMapper.isValidDianCode(dian))
    }

    @Test
    fun `usa el correo corporativo de factura electronica`() {
        // La factura electrónica necesita destinatario y el genérico no aporta
        // uno; sin este buzón la DIAN no tendría a dónde entregarla.
        assertEquals("facturaelectronicapos@permoda.com.co", ConsumidorFinal.build().email)
    }

    @Test
    fun `usa el conmutador corporativo como telefono`() {
        // Cambio de política del 20-ago-2026: antes iba vacío ("no hay teléfono
        // genérico que tenga sentido"). Ahora el genérico lleva el conmutador de
        // Permoda por decisión de negocio.
        assertEquals("6012948999", ConsumidorFinal.build().phone)
    }

    @Test
    fun `el telefono del generico cumple el minimo de 10 digitos`() {
        // Aunque el CF salta la validación de campos obligatorios, este número
        // viaja al maestro de HioPos como cualquier otro. Si alguien lo cambia
        // por uno de 7 dígitos, queda inservible allá y nadie se entera — este
        // test es el aviso.
        val digitos = ConsumidorFinal.build().phone?.count { it.isDigit() } ?: 0
        assertEquals(10, digitos)
    }

    @Test
    fun `el CF pasa la validacion exceptuando telefono e email`() {
        // El CF tiene sus datos fijos y el flujo no pasa por el formulario
        // del cajero: `VendorScreen.applyValidation` deshabilita el botón
        // Aceptar para CONSUMIDOR_FINAL y la validación "real" no corre.
        // La validación pura, en cambio, exige correo y teléfono desde el
        // 14-08-2026; la ruta del CF pasa por alto esa restricción (lo hace
        // el caller, no el validador).
        val result = com.permoda.facturacion.core.validation.CustomerValidator
            .validate(
                ConsumidorFinal.build(),
                rules = com.permoda.facturacion.core.validation.CustomerValidator.Rules(
                    requireEmail = false,
                    requirePhone = false
                )
            )
        assertTrue("el CF debería ser válido, faltó: ${result.summary}", result.isValid)
    }

    @Test
    fun `no inventa customerId`() {
        // HioPosCloud debe resolver su propio cliente genérico.
        assertNull(ConsumidorFinal.build().customerId)
    }

    @Test
    fun `fuerza countryIso2 CO`() {
        // Vive en el campo del modelo, no en extraFieldsXml: `Xml` lo escribe
        // siempre, así que dejarlo en los extras lo duplicaría en la salida.
        assertEquals("CO", ConsumidorFinal.build().countryIso2)
    }

    @Test
    fun `preserva los campos extra del documento en curso`() {
        val extras = "<CustomerField Key=\"salesPersonId\">7</CustomerField>"
        val cf = ConsumidorFinal.build(extras)
        assertTrue(cf.extraFieldsXml.contains("salesPersonId"))
        // countryIso2 NO se mete en los extras.
        assertTrue(!cf.extraFieldsXml.contains("countryIso2"))
    }

    @Test
    fun `matches reconoce al generico e ignora a los demas`() {
        assertTrue(ConsumidorFinal.matches(ConsumidorFinal.build()))
        assertTrue(
            ConsumidorFinal.matches(
                CustomerData(customerName = "X", fiscalId = " 222222222222 ")
            )
        )
        assertFalse(
            ConsumidorFinal.matches(
                CustomerData(customerName = "Juan", fiscalId = "1011086580")
            )
        )
        assertFalse(ConsumidorFinal.matches(CustomerData(customerName = "Juan")))
    }
}
