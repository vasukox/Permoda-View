package com.permoda.facturacion.core.validation

import com.permoda.facturacion.core.model.CustomerData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomerSanitizerTest {

    private val base = CustomerData(
        customerName = "Juan Pérez",
        fiscalDocumentType = "13",
        fiscalId = "1011086580",
        email = "juan.perez@gmail.com",
        phone = "+573111234567"
    )

    @Test
    fun `espacios e invisibles se limpian solos`() {
        val sucio = base.copy(
            customerName = "  Juan\t Pérez​  Gómez ",
            fiscalId = " 1011 086 580 ",
            email = " Juan.Perez@Gmail.com "
        )
        val limpio = CustomerSanitizer.clean(sucio)
        assertEquals("Juan Pérez Gómez", limpio.customerName)
        assertEquals("1011086580", limpio.fiscalId)
        assertEquals("juan.perez@gmail.com", limpio.email)

        assertFalse("el invisible crudo, al totalizar, sí se detecta", CustomerValidator.validate(sucio).isValid)
        assertTrue("en el formulario no bloquea", CustomerValidator.validateForSave(sucio).isValid)
    }

    @Test
    fun `nombre con espacio inicial del maestro se puede editar`() {
        // Caso real del log 0552 (11:02:20): el nombre llegaba como " A…".
        assertTrue(CustomerValidator.validateForSave(base.copy(customerName = " Ana María López")).isValid)
    }

    @Test
    fun `los caracteres especiales visibles no se borran, se avisan`() {
        val sucio = base.copy(customerName = "Juan Pérez 😀@", fiscalId = "1.011.086.580", email = "muñoz@gmail.com")
        assertEquals("Juan Pérez 😀@", CustomerSanitizer.clean(sucio).customerName)
        assertEquals("1.011.086.580", CustomerSanitizer.clean(sucio).fiscalId)

        val result = CustomerValidator.validateForSave(sucio)
        assertFalse(result.isValid)
        assertTrue(result.detail, result.detail.contains("Nombre y apellido: tiene caracteres especiales no permitidos (😀 @)"))
        assertTrue(result.detail, result.detail.contains("Número de documento: tiene caracteres especiales no permitidos (.)"))
        assertTrue(result.detail, result.detail.contains("Correo: tiene caracteres especiales no permitidos (ñ)"))
    }

    @Test
    fun `datos limpios no cambian`() {
        assertEquals(base, CustomerSanitizer.clean(base))
    }
}
