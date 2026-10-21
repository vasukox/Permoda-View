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
    fun `del nombre solo importan los caracteres`() {
        // Desde el 20-oct-2026 solo se valida el juego de caracteres: una palabra
        // o iniciales sueltas pasan si son letras.
        listOf("Juan", "J P").forEach {
            assertTrue("'$it'", CustomerValidator.validate(completo.copy(customerName = it)).isValid)
        }
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

    @Test
    fun `nombre con tildes enie apostrofo y guion es valido`() {
        listOf("José Muñoz", "Ana-María O'Neil", "Dra. Lucía Peña", "Jürgen Müller").forEach { bueno ->
            assertTrue("'$bueno' debería ser válido", CustomerValidator.validate(completo.copy(customerName = bueno)).isValid)
        }
    }

    @Test
    fun `nombre con caracteres especiales se rechaza y los cita`() {
        mapOf(
            "Juan Pérez @" to "@",
            "Juan 😀 Pérez" to "😀",
            "Juan Pérez 3" to "3",
            "Juan#Pérez Gómez" to "#",
            "Juan​Pérez Gómez" to "carácter invisible",
            "Juan Пётр" to "П"
        ).forEach { (malo, citado) ->
            val issue = CustomerValidator.validate(completo.copy(customerName = malo)).issues
                .single { it.field == CustomerValidator.Field.NAME }
            assertEquals(CustomerValidator.Reason.INVALID, issue.reason)
            assertTrue("'$malo' debería citar '$citado': ${issue.message}", issue.message.contains(citado))
        }
    }

    @Test
    fun `los espacios no importan en ningun campo`() {
        val result = CustomerValidator.validate(completo.copy(
            customerName = "  Juan\t  Pérez\n",
            fiscalId = " 1011 086 580 ",
            email = " juan.perez @gmail.com ",
            phone = "+57 311 123 4567"
        ))
        assertTrue(result.detail, result.isValid)
    }

    @Test
    fun `el correo acepta todos los caracteres del estandar`() {
        listOf("o'brien@mail.com", "a!b#c\$d%e&f*g@mail.co", "x=y?z^_`{|}~@sub.dominio.com.co", "j+etiqueta@x.io")
            .forEach { assertTrue("'$it'", CustomerValidator.validate(completo.copy(email = it)).isValid) }
    }

    @Test
    fun `cedula y telefono son exclusivamente numericos`() {
        listOf("1.011.086.580", "1011-086580", "10110865A0").forEach {
            val issue = CustomerValidator.validate(completo.copy(fiscalId = it)).issues.single()
            assertTrue(issue.message, issue.message.contains("caracteres especiales"))
        }
        listOf("(601)1234567", "311-123-4567", "3111234567+").forEach {
            val issue = CustomerValidator.validate(completo.copy(phone = it)).issues.single()
            assertTrue(issue.message, issue.message.contains("Solo se aceptan números"))
        }
    }

    @Test
    fun `razon social con NIT admite numeros y signos de empresa`() {
        val empresa = completo.copy(fiscalDocumentType = "31", fiscalId = "900123456", customerName = "A&B Inversiones 2020, S.A.S.")
        assertTrue(CustomerValidator.validate(empresa).isValid)
        assertFalse(
            "una persona no lleva números en el nombre",
            CustomerValidator.validate(completo.copy(customerName = "A&B Inversiones 2020")).isValid
        )
    }

    @Test
    fun `nombre en descomposicion unicode se normaliza`() {
        // "é" escrito como e + tilde combinante (algunos teclados lo mandan así).
        assertTrue(CustomerValidator.validate(completo.copy(customerName = "José Pérez")).isValid)
    }

    // ------------------------------------------------------------------
    // Correo
    // ------------------------------------------------------------------

    @Test
    fun `correo con caracteres no ascii o puntos dobles se rechaza`() {
        listOf("muñoz@gmail.com", "juan..perez@gmail.com", "juan@@gmail.com", "juan@.gmail.com", "juan(x)@gmail.com").forEach { malo ->
            val result = CustomerValidator.validate(completo.copy(email = malo))
            assertTrue("'$malo' debería ser inválido", result.has(CustomerValidator.Field.EMAIL))
        }
        val issue = CustomerValidator.validate(completo.copy(email = "muñoz@gmail.com")).issues.single()
        assertTrue(issue.message, issue.message.contains("ñ"))
    }

    @Test
    fun `correos malformados se rechazan`() {
        listOf("juan", "juan@", "@gmail.com", "juan@gmail", "juan,perez@gmail.com")
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
        listOf("3111234567", "+57 311 123 4567", "6011234567")
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
