package com.permoda.facturacion.core.validation

import com.permoda.facturacion.core.model.CustomerData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomerValidatorTest {

    private val completo = CustomerData(
        customerName = "Juan Pérez",
        fiscalDocumentType = "13",
        fiscalId = "1011086580",
        email = "juan.perez@gmail.com",
        phone = "+57 311 1234567"
    )

    // ------------------------------------------------------------------
    // Caso feliz
    // ------------------------------------------------------------------

    @Test
    fun `cliente completo es valido`() {
        assertTrue(CustomerValidator.validate(completo).isValid)
    }

    @Test
    fun `telefono vacio no bloquea cuando la regla lo permite`() {
        // Por default ahora es obligatorio; pero si el caller pasa
        // `requirePhone = false`, el validador no lo exige.
        val result = CustomerValidator.validate(
            completo.copy(phone = ""),
            CustomerValidator.Rules(requirePhone = false)
        )
        assertTrue(result.isValid)
    }

    @Test
    fun `telefono vacio bloquea por default desde el 14-08-2026`() {
        // Cambio de política: el teléfono deja de ser opcional. Si en el
        // futuro hay que volver a habilitarlo para algún flujo, se pasa
        // `Rules(requirePhone = false)` desde el caller.
        val result = CustomerValidator.validate(completo.copy(phone = ""))
        assertFalse(result.isValid)
        assertTrue(result.has(CustomerValidator.Field.PHONE))
    }

    @Test
    fun `telefono vacio bloquea si la regla lo exige`() {
        val result = CustomerValidator.validate(
            completo.copy(phone = null),
            CustomerValidator.Rules(requirePhone = true)
        )
        assertFalse(result.isValid)
        assertTrue(result.has(CustomerValidator.Field.PHONE))
    }

    // ------------------------------------------------------------------
    // Escenario 3: cliente que no existe en ningún lado.
    // Antes podía pulsar Aceptar con todo vacío salvo la cédula.
    // ------------------------------------------------------------------

    @Test
    fun `cliente nuevo con solo documento no puede aceptar`() {
        val soloDocumento = CustomerData(
            customerName = "",
            fiscalDocumentType = "13",
            fiscalId = "1011086580",
            email = "",
            phone = null
        )
        val result = CustomerValidator.validate(soloDocumento)

        assertFalse(result.isValid)
        assertTrue(result.has(CustomerValidator.Field.NAME))
        assertTrue(result.has(CustomerValidator.Field.EMAIL))
        assertFalse(result.has(CustomerValidator.Field.DOC_ID))
    }

    @Test
    fun `cliente totalmente vacio reporta los cinco obligatorios`() {
        // Desde el 14-08-2026 el teléfono también es obligatorio por defecto.
        val vacio = CustomerData(customerName = "")
        val result = CustomerValidator.validate(vacio)

        assertEquals(
            setOf(
                CustomerValidator.Field.NAME,
                CustomerValidator.Field.DOC_TYPE,
                CustomerValidator.Field.DOC_ID,
                CustomerValidator.Field.EMAIL,
                CustomerValidator.Field.PHONE
            ),
            result.fields.toSet()
        )
    }

    // ------------------------------------------------------------------
    // Nombre
    // ------------------------------------------------------------------

    @Test
    fun `nombre de una sola palabra es invalido`() {
        val result = CustomerValidator.validate(completo.copy(customerName = "Juan"))
        assertFalse(result.isValid)
        assertTrue(result.has(CustomerValidator.Field.NAME))
    }

    @Test
    fun `nombre con iniciales sueltas es invalido`() {
        val result = CustomerValidator.validate(completo.copy(customerName = "J P"))
        assertFalse(result.isValid)
    }

    @Test
    fun `nombre en blanco cuenta como faltante no como invalido`() {
        val result = CustomerValidator.validate(completo.copy(customerName = "   "))
        assertTrue(
            result.issues.any {
                it.field == CustomerValidator.Field.NAME &&
                    it.reason == CustomerValidator.Reason.MISSING
            }
        )
    }

    @Test
    fun `nombre con tres palabras es valido`() {
        assertTrue(
            CustomerValidator.validate(
                completo.copy(customerName = "Juan Carlos Pérez")
            ).isValid
        )
    }

    // ------------------------------------------------------------------
    // Correo
    // ------------------------------------------------------------------

    @Test
    fun `correos malformados se rechazan`() {
        listOf("juan", "juan@", "@gmail.com", "juan@gmail", "juan perez@gmail.com")
            .forEach { malo ->
                val result = CustomerValidator.validate(completo.copy(email = malo))
                assertFalse("'$malo' debería ser inválido", result.isValid)
                assertTrue(result.has(CustomerValidator.Field.EMAIL))
            }
    }

    @Test
    fun `correos validos se aceptan`() {
        listOf(
            "juan.perez@gmail.com",
            "j+etiqueta@sub.dominio.co",
            "JUAN_PEREZ-99@permoda.com.co"
        ).forEach { bueno ->
            assertTrue(
                "'$bueno' debería ser válido",
                CustomerValidator.validate(completo.copy(email = bueno)).isValid
            )
        }
    }

    // ------------------------------------------------------------------
    // Documento
    // ------------------------------------------------------------------

    @Test
    fun `cedula con menos de cuatro digitos es invalida`() {
        val result = CustomerValidator.validate(completo.copy(fiscalId = "123"))
        assertFalse(result.isValid)
        assertTrue(result.has(CustomerValidator.Field.DOC_ID))
    }

    @Test
    fun `cedula con letras es invalida`() {
        val result = CustomerValidator.validate(completo.copy(fiscalId = "10110AB80"))
        assertFalse(result.isValid)
    }

    @Test
    fun `pasaporte admite alfanumerico`() {
        val result = CustomerValidator.validate(
            completo.copy(fiscalDocumentType = "41", fiscalId = "AV123456")
        )
        assertTrue(result.isValid)
    }

    @Test
    fun `tipo de documento no mapeable es invalido`() {
        val result = CustomerValidator.validate(completo.copy(fiscalDocumentType = "XX"))
        assertFalse(result.isValid)
        assertTrue(result.has(CustomerValidator.Field.DOC_TYPE))
    }

    @Test
    fun `acepta tipo de documento como nombre corto y como nombre completo`() {
        assertTrue(CustomerValidator.validate(completo.copy(fiscalDocumentType = "CC")).isValid)
        assertTrue(
            CustomerValidator.validate(
                completo.copy(fiscalDocumentType = "Cédula de ciudadanía")
            ).isValid
        )
    }

    // ------------------------------------------------------------------
    // Teléfono
    // ------------------------------------------------------------------

    @Test
    fun `telefono demasiado corto es invalido`() {
        assertFalse(CustomerValidator.validate(completo.copy(phone = "12345")).isValid)
    }

    @Test
    fun `telefono con letras es invalido`() {
        assertFalse(CustomerValidator.validate(completo.copy(phone = "311 LLAMAME")).isValid)
    }

    @Test
    fun `telefono con formato colombiano es valido`() {
        listOf("3111234567", "+57 311 123 4567", "(601) 123-4567")
            .forEach { bueno ->
                assertTrue(
                    "'$bueno' debería ser válido",
                    CustomerValidator.validate(completo.copy(phone = bueno)).isValid
                )
            }
    }

    @Test
    fun `el minimo son 10 digitos locales`() {
        // Antes el mínimo era 7 (heredado de los fijos de siete cifras) y un
        // celular a medio teclear habilitaba el botón Aceptar.
        assertFalse(
            "9 dígitos es un celular incompleto",
            CustomerValidator.validate(completo.copy(phone = "311123456")).isValid
        )
        assertTrue(
            "10 dígitos es un celular completo",
            CustomerValidator.validate(completo.copy(phone = "3111234567")).isValid
        )
    }

    @Test
    fun `los indicativos soportados se descuentan`() {
        // El valor que viaja al POS lleva el indicativo pegado
        // (`CustomerMirror.buildPhoneFullNumber`), así que contar dígitos a secas
        // dejaría pasar números cortos: "+573111234" son 10 dígitos en total pero
        // sólo 7 de número.
        //
        // ⚠️ Si se agrega un país al selector de las pantallas, su indicativo
        // tiene que entrar también en `PHONE_DIAL_PREFIXES` o los números de ese
        // país se rechazarán por cortos.
        listOf("+57", "+593", "+506", "+00").forEach { dial ->
            assertTrue(
                "$dial + 10 dígitos debe ser válido",
                CustomerValidator.validate(completo.copy(phone = "${dial}3111234567")).isValid
            )
            assertFalse(
                "$dial + 9 dígitos debe ser inválido",
                CustomerValidator.validate(completo.copy(phone = "${dial}311123456")).isValid
            )
        }
    }

    @Test
    fun `un numero sin mas que el indicativo no pasa`() {
        assertFalse(CustomerValidator.validate(completo.copy(phone = "+57")).isValid)
    }

    // ------------------------------------------------------------------
    // Mensajes
    // ------------------------------------------------------------------

    @Test
    fun `summary separa faltantes de invalidos`() {
        val result = CustomerValidator.validate(
            completo.copy(customerName = "", email = "no-es-correo")
        )
        assertTrue(result.summary.contains("Faltan"))
        assertTrue(result.summary.contains("Nombre y apellido"))
        assertTrue(result.summary.contains("Revisa"))
        assertTrue(result.summary.contains("Correo"))
    }

    @Test
    fun `summary vacio cuando es valido`() {
        assertEquals("", CustomerValidator.validate(completo).summary)
    }
}
