package com.permoda.facturacion.core

import com.permoda.facturacion.core.customer.ConsumidorFinal
import com.permoda.facturacion.core.scenario.CustomerScenario
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Assert.fail

class XmlTest {

    @Test
    fun `parses CustomerResult into CustomerData`() {
        val xml = """
            <CustomerResult>
              <CustomerField Key="customerId">123</CustomerField>
              <CustomerField Key="customerName">Juan Pérez Gómez</CustomerField>
              <CustomerField Key="fiscalDocumentType">DNI</CustomerField>
              <CustomerField Key="fiscalId">12345678</CustomerField>
              <CustomerField Key="email">juan@empresa.com</CustomerField>
              <CustomerField Key="phone">+54 9 11 1234 5678</CustomerField>
              <CustomerField Key="city">Buenos Aires</CustomerField>
              <CustomerField Key="countryCode">AR</CustomerField>
            </CustomerResult>
        """.trimIndent()

        val parsed = Xml.parseCustomer(xml)
        assertEquals("123", parsed.data.customerId)
        assertEquals("Juan Pérez Gómez", parsed.data.customerName)
        assertEquals("DNI", parsed.data.fiscalDocumentType)
        assertEquals("12345678", parsed.data.fiscalId)
        assertEquals("juan@empresa.com", parsed.data.email)
        assertEquals("+54 9 11 1234 5678", parsed.data.phone)
        assertTrue(parsed.extraFieldsXml.contains("city"))
        assertTrue(parsed.extraFieldsXml.contains("countryCode"))
        // Los 5 campos pintados NO deben estar en extra.
        assertTrue(!parsed.extraFieldsXml.contains("customerName"))
    }

    @Test
    fun `accepts XML without wrapper (Customer root)`() {
        val xml = """
            <Customer>
              <CustomerField Key="customerName">Solo Nombre</CustomerField>
            </Customer>
        """.trimIndent()
        val parsed = Xml.parseCustomer(xml)
        assertEquals("Solo Nombre", parsed.data.customerName)
        assertTrue(parsed.extraFieldsXml.isEmpty())
    }

    @Test
    fun `rejects oversized xml payloads`() {
        val huge = "<Customer>" + "X".repeat(1024 * 1024) + "</Customer>"
        try {
            Xml.parseCustomer(huge)
            fail("Debió lanzar IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("tamaño") == true)
        }
    }

    @Test
    fun `escapes XML special characters on writeCustomerResult`() {
        val parsed = Xml.parseCustomer("""
            <CustomerResult>
              <CustomerField Key="customerName">A &amp; B &lt;c&gt;</CustomerField>
              <CustomerField Key="fiscalId">123</CustomerField>
            </CustomerResult>
        """.trimIndent())
        // Tras parsear, el DOM nos da los valores des-escapados
        assertEquals("A & B <c>", parsed.data.customerName)
        // Al re-escribir, deben volver a escaparse
        val xmlOut = Xml.writeCustomerResult(parsed.data, parsed.extraFieldsXml)
        assertTrue("Debe re-escapar & a &amp;", xmlOut.contains("A &amp; B &lt;c&gt;"))
    }

    @Test
    fun `preserves extra fields untouched on writeCustomerResult`() {
        val parsed = Xml.parseCustomer("""
            <CustomerResult>
              <CustomerField Key="customerName">Juan</CustomerField>
              <CustomerField Key="city">Bogotá</CustomerField>
              <CustomerField Key="email">a@b.com</CustomerField>
            </CustomerResult>
        """.trimIndent())
        val xmlOut = Xml.writeCustomerResult(parsed.data, parsed.extraFieldsXml)
        assertTrue(xmlOut.contains("<CustomerField Key=\"city\">Bogotá</CustomerField>"))
        assertTrue(xmlOut.contains("<CustomerField Key=\"customerName\">Juan</CustomerField>"))
        assertTrue(xmlOut.contains("<CustomerField Key=\"email\">a@b.com</CustomerField>"))
    }

    @Test
    fun `handles empty optional fields`() {
        val parsed = Xml.parseCustomer("""
            <CustomerResult>
              <CustomerField Key="customerName">Solo Nombre</CustomerField>
            </CustomerResult>
        """.trimIndent())
        assertEquals(null, parsed.data.email)
        assertEquals(null, parsed.data.phone)
        assertEquals(null, parsed.data.fiscalId)
    }

    // ------------------------------------------------------------------
    // Koaj Lovers: la decisión viaja al POS como un CustomerField propio.
    // ------------------------------------------------------------------

    private val base = """
        <CustomerResult>
          <CustomerField Key="customerName">Juan Pérez</CustomerField>
          <CustomerField Key="email">a@b.com</CustomerField>
          <CustomerField Key="city">Bogotá</CustomerField>
        </CustomerResult>
    """.trimIndent()

    @Test
    fun `escribe koajLoversOptIn true cuando el cliente se une`() {
        val parsed = Xml.parseCustomer(base)
        val out = Xml.writeCustomerResult(parsed.data, parsed.extraFieldsXml, loyaltyOptIn = true)
        assertTrue(out.contains("<CustomerField Key=\"koajLoversOptIn\">true</CustomerField>"))
    }

    @Test
    fun `escribe koajLoversOptIn false cuando declina`() {
        val parsed = Xml.parseCustomer(base)
        val out = Xml.writeCustomerResult(parsed.data, parsed.extraFieldsXml, loyaltyOptIn = false)
        // Un false explícito no es lo mismo que ausencia: distingue
        // "dijo que no" de "no se le preguntó".
        assertTrue(out.contains("<CustomerField Key=\"koajLoversOptIn\">false</CustomerField>"))
    }

    @Test
    fun `omite koajLoversOptIn cuando la pantalla esta deshabilitada`() {
        val parsed = Xml.parseCustomer(base)
        val out = Xml.writeCustomerResult(parsed.data, parsed.extraFieldsXml, loyaltyOptIn = null)
        assertTrue(!out.contains("koajLoversOptIn"))
    }

    @Test
    fun `no duplica koajLoversOptIn en un round-trip`() {
        // El POS nos devuelve un cliente que ya traía la decisión guardada.
        val conOptIn = """
            <CustomerResult>
              <CustomerField Key="customerName">Juan Pérez</CustomerField>
              <CustomerField Key="koajLoversOptIn">true</CustomerField>
              <CustomerField Key="city">Bogotá</CustomerField>
            </CustomerResult>
        """.trimIndent()

        val parsed = Xml.parseCustomer(conOptIn)
        // No debe quedar en los extras, o se escribiría dos veces.
        assertTrue(!parsed.extraFieldsXml.contains("koajLoversOptIn"))

        val out = Xml.writeCustomerResult(parsed.data, parsed.extraFieldsXml, loyaltyOptIn = false)
        assertEquals(1, Regex("koajLoversOptIn").findAll(out).count())
        assertTrue(out.contains("<CustomerField Key=\"koajLoversOptIn\">false</CustomerField>"))
    }

    @Test
    fun `readLoyaltyOptIn lee la decision previa`() {
        val conOptIn = """
            <CustomerResult>
              <CustomerField Key="koajLoversOptIn">true</CustomerField>
            </CustomerResult>
        """.trimIndent()
        assertEquals(true, Xml.readLoyaltyOptIn(conOptIn))
        assertEquals(null, Xml.readLoyaltyOptIn(base))
        assertEquals(false, Xml.readLoyaltyOptIn(
            """<CustomerResult><CustomerField Key="koajLoversOptIn">false</CustomerField></CustomerResult>"""
        ))
    }

    @Test
    fun `readLoyaltyOptIn no lanza con xml basura`() {
        assertEquals(null, Xml.readLoyaltyOptIn("no soy xml"))
    }

    // ------------------------------------------------------------------
    // Escenario en el XML de salida: el POS necesita saber si tiene que
    // crear el cliente o si es el genérico.
    // ------------------------------------------------------------------

    @Test
    fun `escribe el escenario y el flag de consumidor final`() {
        val parsed = Xml.parseCustomer(base)
        val out = Xml.writeCustomerResult(
            parsed.data,
            parsed.extraFieldsXml,
            scenario = CustomerScenario.CONSUMIDOR_FINAL
        )
        assertTrue(out.contains("<CustomerField Key=\"permodaScenario\">CONSUMIDOR_FINAL</CustomerField>"))
        assertTrue(out.contains("<CustomerField Key=\"isConsumidorFinal\">true</CustomerField>"))
    }

    @Test
    fun `isConsumidorFinal es false en los escenarios nominales`() {
        val parsed = Xml.parseCustomer(base)
        listOf(
            CustomerScenario.EDIT,
            CustomerScenario.CREATE_FROM_DIAN,
            CustomerScenario.CREATE_MANUAL
        ).forEach { scenario ->
            val out = Xml.writeCustomerResult(
                parsed.data, parsed.extraFieldsXml, scenario = scenario
            )
            assertTrue(
                "$scenario debería marcar isConsumidorFinal=false",
                out.contains("<CustomerField Key=\"isConsumidorFinal\">false</CustomerField>")
            )
            assertTrue(out.contains("<CustomerField Key=\"permodaScenario\">${scenario.name}</CustomerField>"))
        }
    }

    @Test
    fun `omite el escenario si no se pasa`() {
        val parsed = Xml.parseCustomer(base)
        val out = Xml.writeCustomerResult(parsed.data, parsed.extraFieldsXml)
        assertTrue(!out.contains("permodaScenario"))
        assertTrue(!out.contains("isConsumidorFinal"))
    }

    @Test
    fun `no duplica los campos propios en un round-trip`() {
        val conCampos = """
            <CustomerResult>
              <CustomerField Key="customerName">Juan Pérez</CustomerField>
              <CustomerField Key="permodaScenario">CREATE_MANUAL</CustomerField>
              <CustomerField Key="isConsumidorFinal">false</CustomerField>
              <CustomerField Key="koajLoversOptIn">true</CustomerField>
              <CustomerField Key="city">Bogotá</CustomerField>
            </CustomerResult>
        """.trimIndent()

        val parsed = Xml.parseCustomer(conCampos)
        assertTrue(!parsed.extraFieldsXml.contains("permodaScenario"))
        assertTrue(!parsed.extraFieldsXml.contains("isConsumidorFinal"))

        val out = Xml.writeCustomerResult(
            parsed.data,
            parsed.extraFieldsXml,
            loyaltyOptIn = false,
            scenario = CustomerScenario.EDIT
        )
        assertEquals(1, Regex("permodaScenario").findAll(out).count())
        assertEquals(1, Regex("isConsumidorFinal").findAll(out).count())
        assertEquals(1, Regex("koajLoversOptIn").findAll(out).count())
        assertTrue(out.contains("Bogotá"))
    }

    // ------------------------------------------------------------------
    // Campos del contrato de ICG que antes no escribíamos.
    // ------------------------------------------------------------------

    @Test
    fun `escribe isInvoice y electronicInvoice segun Invoicing`() {
        val parsed = Xml.parseCustomer(base)

        val conFactura = Xml.writeCustomerResult(parsed.data, parsed.extraFieldsXml, invoicing = true)
        assertTrue(conFactura.contains("<CustomerField Key=\"isInvoice\">true</CustomerField>"))
        assertTrue(conFactura.contains("<CustomerField Key=\"electronicInvoice\">true</CustomerField>"))

        val sinFactura = Xml.writeCustomerResult(parsed.data, parsed.extraFieldsXml, invoicing = false)
        assertTrue(sinFactura.contains("<CustomerField Key=\"isInvoice\">false</CustomerField>"))
        assertTrue(sinFactura.contains("<CustomerField Key=\"electronicInvoice\">false</CustomerField>"))
    }

    @Test
    fun `ya no escribimos generateInvoice, que no existe en el contrato`() {
        // HU-HIOPOS · La llave del contrato es `isInvoice` (HPCL.025, págs. 39-40).
        // `generateInvoice` era invención nuestra y HioPosCloud la descartaba.
        val parsed = Xml.parseCustomer(base)
        val out = Xml.writeCustomerResult(parsed.data, parsed.extraFieldsXml, invoicing = true)
        assertTrue(
            "generateInvoice no debe volver a la salida",
            !out.contains("generateInvoice")
        )
    }

    @Test
    fun `un generateInvoice que venga del POS no se reenvia`() {
        // Un cliente guardado por una versión anterior puede traer la llave vieja
        // en el maestro del POS. Reenviarla solo propagaría el error.
        val conLlaveVieja = """
            <CustomerResult>
              <CustomerField Key="customerName">Juan Perez</CustomerField>
              <CustomerField Key="generateInvoice">true</CustomerField>
            </CustomerResult>
        """.trimIndent()
        val parsed = Xml.parseCustomer(conLlaveVieja)
        val out = Xml.writeCustomerResult(parsed.data, parsed.extraFieldsXml)
        assertTrue(!out.contains("generateInvoice"))
    }

    @Test
    fun `no escribimos llaves que el contrato no define`() {
        // HU-HIOPOS · Guardián de contrato. Esta es la lista literal de llaves de
        // la estructura CustomerResult del HPCL.025 (págs. 39-40). Cualquier llave
        // nueva que no esté acá —o que no sea un campo propio declarado— revienta
        // este test antes de llegar a una terminal, que es exactamente lo que no
        // pasó con `generateInvoice`.
        val llavesDelContrato = setOf(
            "customerId", "customerName", "gender", "fiscalDocumentType", "fiscalId",
            "address", "roadNumber", "block", "stairCase", "floor", "door", "city",
            "state", "email", "sendDocumentByEmail", "sendServiceConfirmation",
            "postalCode", "phone", "isInvoice", "electronicInvoice",
            "billWithoutTaxes", "discountReasonId", "observations", "priceListId",
            "taxExemption", "hndNumOCExenta", "hndNumRegExonerado", "hndNumRegSAG",
            "billRegimeId", "exemptTaxId", "countryId", "countryName", "countryCode",
            "stateId", "cityId", "responsabilitiesId", "applyLinkedTax",
            "externalCustomerId", "countryIso2"
        )
        // Campos propios de PermodaView. Están fuera del contrato a propósito y
        // son inofensivos para un consumidor que los ignore.
        val camposPropios = setOf(
            Xml.KEY_LOYALTY_OPT_IN, Xml.KEY_SCENARIO, Xml.KEY_IS_CONSUMIDOR_FINAL
        )

        val parsed = Xml.parseCustomer(base)
        val out = Xml.writeCustomerResult(
            data = parsed.data,
            extraFieldsXml = parsed.extraFieldsXml,
            loyaltyOptIn = true,
            scenario = CustomerScenario.CREATE_FROM_DIAN,
            invoicing = true
        )

        val escritas = Regex("Key=\"([^\"]+)\"").findAll(out).map { it.groupValues[1] }.toSet()
        val desconocidas = escritas - llavesDelContrato - camposPropios
        assertTrue(
            "Llaves que no están en el contrato ni declaradas como propias: $desconocidas",
            desconocidas.isEmpty()
        )
    }

    @Test
    fun `countryIso2 cae a CO cuando el POS no lo manda`() {
        val parsed = Xml.parseCustomer(base)
        val out = Xml.writeCustomerResult(parsed.data, parsed.extraFieldsXml)
        assertTrue(out.contains("<CustomerField Key=\"countryIso2\">CO</CustomerField>"))
    }

    @Test
    fun `countryIso2 respeta el pais que mando el POS`() {
        // No le cambiamos el país a un cliente que ya lo traía.
        val conPais = """
            <CustomerResult>
              <CustomerField Key="customerName">John Doe</CustomerField>
              <CustomerField Key="countryIso2">US</CustomerField>
            </CustomerResult>
        """.trimIndent()
        val parsed = Xml.parseCustomer(conPais)
        assertEquals("US", parsed.data.countryIso2)

        val out = Xml.writeCustomerResult(parsed.data, parsed.extraFieldsXml)
        assertTrue(out.contains("<CustomerField Key=\"countryIso2\">US</CustomerField>"))
        assertEquals(1, Regex("countryIso2").findAll(out).count())
    }

    @Test
    fun `los campos de factura no se duplican en un round-trip`() {
        val parsed = Xml.parseCustomer(base)
        val primera = Xml.writeCustomerResult(parsed.data, parsed.extraFieldsXml)
        val reparsed = Xml.parseCustomer(primera)
        val segunda = Xml.writeCustomerResult(reparsed.data, reparsed.extraFieldsXml)

        listOf("countryIso2", "isInvoice", "electronicInvoice").forEach { key ->
            assertEquals("$key duplicado", 1, Regex(key).findAll(segunda).count())
        }
    }

    @Test
    fun `el consumidor final sobrevive el round-trip completo`() {
        val cf = ConsumidorFinal.build("<CustomerField Key=\"city\">Bogotá</CustomerField>")
        val out = Xml.writeCustomerResult(
            cf, cf.extraFieldsXml, scenario = CustomerScenario.CONSUMIDOR_FINAL
        )
        val reparsed = Xml.parseCustomer(out)

        assertEquals("Consumidor Final", reparsed.data.customerName)
        assertEquals("222222222222", reparsed.data.fiscalId)
        assertEquals("13", reparsed.data.fiscalDocumentType)
        assertTrue(ConsumidorFinal.matches(reparsed.data))
    }
}
