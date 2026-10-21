package com.permoda.facturacion.core.manage

import com.permoda.facturacion.core.customer.CustomerType
import com.permoda.facturacion.core.hka.TipoDocumentoMapper
import com.permoda.facturacion.core.model.CustomerData
import org.junit.Assert.*
import org.junit.Test
import javax.xml.parsers.DocumentBuilderFactory

class ManageCustomerContractTest {
    // IDs deliberadamente distintos de los IDs internos y de persona natural/jurídica.
    private val config = ManageCustomerConfig(CustomerCategoryMapping(10, 47, 81))
    private fun xml(id: String? = "123", category: String? = "10", extra: String = ""): String =
        "<CustomerResult>" + (id?.let { "<CustomerField Key=\"customerId\">$it</CustomerField>" } ?: "") +
            (category?.let { "<CustomerField Key=\"customerTypeId\">$it</CustomerField>" } ?: "") +
            "<CustomerField Key=\"customerName\">Persona Prueba</CustomerField>" +
            "<CustomerField Key=\"fiscalId\">12345678</CustomerField>" +
            "<CustomerField Key=\"fiscalDocumentType\">9</CustomerField>" +
            "<CustomerField Key=\"email\">persona@example.test</CustomerField>" +
            "<CustomerField Key=\"phone\">3001234567</CustomerField>$extra</CustomerResult>"
    private fun fields(xml: String): Map<String, String> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(xml.byteInputStream())
        val list = doc.getElementsByTagName("CustomerField")
        return (0 until list.length).associate {
            val node = list.item(it) as org.w3c.dom.Element
            node.getAttribute("Key") to node.textContent
        }
    }

    @Test fun `sin parametros usa el catalogo de Permoda y los IDs deben ser validos`() {
        assertEquals(CustomerCategoryMapping(1, 2, 3), ManageCustomerConfig.fromParametersXml("").categories)
        assertThrows(IllegalArgumentException::class.java) { CustomerCategoryMapping(1, 1, 2) }
        assertThrows(IllegalArgumentException::class.java) { CustomerCategoryMapping(-1, 1, 2) }
    }

    @Test fun `perfil de entrada configurable sin aliases heuristicas`() {
        val p = """<Parameters><Param Key="CUSTOMER_TYPE_NORMAL_ID">10</Param>
            <Param Key="CUSTOMER_TYPE_VIP_ID">47</Param><Param Key="CUSTOMER_TYPE_EMPLOYEE_ID">81</Param>
            <Param Key="MANAGE_CUSTOMER_INPUT_EXTRA">Customer</Param>
            <Param Key="MANAGE_CUSTOMER_INPUT_URI_EXTRA">CustomerUri</Param>
            <Param Key="MANAGE_CUSTOMER_ID_EXTRA">nativeId</Param></Parameters>"""
        val profile = ManageCustomerConfig.fromParametersXml(p)
        assertEquals("Customer", profile.inputExtra)
        assertEquals("nativeId", profile.identityExtra)
        assertEquals(47, profile.categories?.vip)
    }

    @Test fun `configuracion parcial del catalogo se rechaza`() {
        val parcial = """<Parameters><Param Key="CUSTOMER_TYPE_NORMAL_ID">10</Param></Parameters>"""
        assertThrows(IllegalArgumentException::class.java) { ManageCustomerConfig.fromParametersXml(parcial) }
    }

    @Test fun `catalogo de Permoda normal que acepta Koaj sale como VIP 2 y empleado se conserva`() {
        val permoda = ManageCustomerConfig.fromParametersXml("")
        fun out(category: String, optIn: Boolean?): String? {
            val s = ManagedCustomerSession.parse(xml(category = category), permoda)
            return fields(s.resultXml(s.parsed.data, optIn))["customerTypeId"]
        }
        assertEquals("2", out("1", true))
        assertEquals("1", out("1", false))
        assertEquals("2", out("2", false))
        assertEquals("3", out("3", true))
    }

    @Test fun `modo sin catalogo devuelve la categoria recibida y permite editar sin ella`() {
        val unmapped = ManageCustomerConfig(categories = null)
        listOf(true, false, null).forEach { optIn ->
            val session = ManagedCustomerSession.parse(xml(category = "999"), unmapped)
            assertEquals("999", fields(session.resultXml(session.parsed.data, optIn))["customerTypeId"])
        }
        val sinCategoria = ManagedCustomerSession.parse(xml(category = null), unmapped)
        assertEquals(CustomerOperation.EDIT, sinCategoria.operation)
        assertFalse(fields(sinCategoria.resultXml(sinCategoria.parsed.data, true)).containsKey("customerTypeId"))
    }

    @Test fun `nombre cedula y datos completos no sustituyen identidad`() {
        assertThrows(IllegalArgumentException::class.java) { ManagedCustomerSession.parse(xml(id = null), config) }
    }

    @Test fun `todos los IDs provisionales representan alta aunque haya cedula`() {
        listOf("0", "-1", "-2", "-999").forEach { id ->
            val session = ManagedCustomerSession.parse(xml(id), config)
            assertEquals(CustomerOperation.CREATE, session.operation)
            assertNull(session.parsed.data.customerId)
            assertTrue(session.shouldQueryHka)
            assertEquals(ManagementStart.CREATE_LOOKUP, ManagementStart.resolve(session.operation, session.parsed.data))
        }
    }

    @Test fun `identidad extra nativa aceptada y discrepancias rechazadas`() {
        assertEquals(CustomerOperation.CREATE, ManagedCustomerSession.parse(xml(id = null), config, "-1").operation)
        assertThrows(IllegalArgumentException::class.java) { ManagedCustomerSession.parse(xml(), config, "456") }
        assertThrows(IllegalArgumentException::class.java) { ManagedCustomerSession.parse(xml("no-id"), config) }
    }

    @Test fun `edicion nunca consulta HKA incluso tras corregir documento`() {
        val session = ManagedCustomerSession.parse(xml(), config)
        assertEquals(ManagementStart.EDIT_FORM, ManagementStart.resolve(session.operation, session.parsed.data.copy(fiscalId = "")))
        assertFalse(session.shouldQueryHka)
        assertThrows(IllegalStateException::class.java) { session.forDocument("13", "87654321") }
        val result = fields(session.resultXml(session.parsed.data.copy(customerId = "999", fiscalId = "87654321"), false))
        assertEquals("123", result["customerId"])
        assertEquals("87654321", result["fiscalId"])
    }

    @Test fun `alta sin documento espera captura y no pierde identidad de operacion`() {
        assertEquals(ManagementStart.DOCUMENT_CAPTURE, ManagementStart.resolve(CustomerOperation.CREATE, CustomerData(customerName = "")))
        val session = ManagedCustomerSession.parse(xml("-1"), config)
        assertNull(fields(session.resultXml(session.parsed.data, false))["customerId"])
        val another = session.forDocument("13", "87654321")
        assertEquals("", another.customerName)
        assertEquals("", another.email)
        assertNull(another.customerId)
    }

    @Test fun `edicion sin categoria o desconocida se permite y no inventa tipo`() {
        // Antes se bloqueaba la edición; ahora abre como Estándar y conserva lo que traía.
        val desconocida = ManagedCustomerSession.parse(xml(category = "0"), config)
        assertEquals(CustomerOperation.EDIT, desconocida.operation)
        assertEquals("0", fields(desconocida.resultXml(desconocida.parsed.data, false))["customerTypeId"])
        assertEquals("0", fields(desconocida.resultXml(desconocida.parsed.data, null))["customerTypeId"])
        // Aceptar Koaj Lovers sí la convierte en VIP.
        assertEquals("47", fields(desconocida.resultXml(desconocida.parsed.data, true))["customerTypeId"])

        val sinCategoria = ManagedCustomerSession.parse(xml(category = null), config)
        assertFalse(fields(sinCategoria.resultXml(sinCategoria.parsed.data, false)).containsKey("customerTypeId"))
        assertEquals("999", fields(ManagedCustomerSession.parse(xml(category = "999"), config)
            .let { it.resultXml(it.parsed.data, false) })["customerTypeId"])
    }

    @Test fun `extras de HioPos con tipo 0 y fecha de nacimiento abren la edicion`() {
        val session = ManagedCustomerSession.fromPosExtras(mapOf(
            "customerName" to "Persona Prueba", "fiscalId" to "12345678", "fiscalDocumentType" to 9,
            "email" to "persona@prueba.com", "phone" to "3111234567", "customerTypeId" to 0,
            "birthDate" to java.sql.Date.valueOf("1990-04-18")
        ), ManageCustomerConfig.fromParametersXml(""))
        assertEquals(CustomerOperation.EDIT, session.operation)
        val out = fields(session.resultXml(session.parsed.data, false))
        assertEquals("0", out["customerTypeId"])
        assertEquals("1990-04-18", out["birthDate"])
    }

    @Test fun `membresia se convierte al catalogo remoto y preserva empleado y VIP`() {
        listOf(Triple("10", true, "47"), Triple("10", false, "10"), Triple("47", false, "47"),
            Triple("81", true, "81"), Triple("81", false, "81")).forEach { (incoming, optIn, expected) ->
            val session = ManagedCustomerSession.parse(xml(category = incoming), config)
            val output = fields(session.resultXml(session.parsed.data.copy(customerTypeId = CustomerType.VIP.id), optIn))
            assertEquals(expected, output["customerTypeId"])
            assertFalse(output.containsKey("CustomerTypeId"))
        }
        val session = ManagedCustomerSession.parse(xml(), config)
        assertEquals("10", fields(session.resultXml(session.parsed.data, null))["customerTypeId"])
    }

    @Test fun `naturaleza fiscal permisos y campos originales no se convierten en membresia`() {
        val session = ManagedCustomerSession.parse(xml(extra = """<CustomerField Key="contactTypeId">2</CustomerField>
            <CustomerField Key="allowPromos">false</CustomerField><CustomerField Key="city">A &amp; B</CustomerField>"""), config)
        // Razón social (NIT): una persona ya no puede llevar "&" en el nombre.
        val result = session.resultXml(session.parsed.data.copy(fiscalDocumentType = "31", customerName = "Persona &amp Prueba"), true)
        val f = fields(result)
        assertEquals("2", f["contactTypeId"])
        assertEquals("false", f["allowPromos"])
        assertEquals("47", f["customerTypeId"])
        assertEquals("A & B", f["city"])
        assertEquals("Persona &amp Prueba", f["customerName"])
        assertFalse(f.containsKey("koajLoversOptIn"))
    }

    @Test fun `CustomerSearch va anidado y usa campo BD FiscalId y documento final`() {
        val session = ManagedCustomerSession.parse(xml(), config)
        val result = session.resultXml(session.parsed.data.copy(fiscalId = "87654321"), false)
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(result.byteInputStream())
        val search = doc.getElementsByTagName("CustomerSearch").item(0)
        assertEquals("CustomerResult", search.parentNode.nodeName)
        assertTrue(result.contains("Key=\"searchField\">FiscalId<"))
        assertTrue(result.contains("Key=\"searchValue\">87654321<"))
    }

    @Test fun `extras sueltos de HioPos 15_14_3 alta solo con documento contacto`() {
        val session = ManagedCustomerSession.fromPosExtras(
            mapOf("LanguageISO" to "es", "fiscalId" to "12345678", "email" to "", "phone" to ""), config
        )
        assertEquals(CustomerOperation.CREATE, session.operation)
        assertNull(session.parsed.data.customerId)
        assertEquals("12345678", session.parsed.data.fiscalId)
        assertTrue(session.shouldQueryHka)
    }

    @Test fun `extras sueltos de HioPos 15_14_3 edicion con ficha completa y tipos nativos`() {
        val extras = mapOf<String, Any?>("LanguageISO" to "es", "fiscalId" to "12345678", "email" to "persona@example.test",
            "phone" to "3001234567", "customerName" to "Persona Prueba", "fiscalDocumentType" to 9, "contactTypeId" to 1,
            "customerTypeId" to 81, "allowPromos" to false, "birthDate" to null, "city" to "Bogotá")
        val session = ManagedCustomerSession.fromPosExtras(extras, config)
        assertEquals(CustomerOperation.EDIT, session.operation)
        assertFalse(session.shouldQueryHka)
        val out = fields(session.resultXml(session.parsed.data, true))
        assertNull(out["customerId"])
        assertEquals("81", out["customerTypeId"])
        assertEquals("1", out["contactTypeId"])
        assertEquals("Bogotá", out["city"])
        assertFalse(out.containsKey("birthDate"))
        assertFalse(out.containsKey("LanguageISO"))
    }

    @Test fun `recordatorio de buscar solo en el boton Nuevo cliente`() {
        val boton = ManagedCustomerSession.fromPosExtras(mapOf("fiscalId" to "", "email" to "", "phone" to ""), config)
        assertTrue(ManagementStart.needsSearchReminder(boton.operation, boton.parsed.data))
        val avisoNoEncontrado = ManagedCustomerSession.fromPosExtras(mapOf("fiscalId" to "12345678", "email" to "", "phone" to ""), config)
        assertFalse(ManagementStart.needsSearchReminder(avisoNoEncontrado.operation, avisoNoEncontrado.parsed.data))
        val edicion = ManagedCustomerSession.parse(xml(), config)
        assertFalse(ManagementStart.needsSearchReminder(edicion.operation, edicion.parsed.data.copy(fiscalId = "")))
    }

    @Test fun `sin XML ni campos del POS se rechaza`() {
        assertThrows(IllegalArgumentException::class.java) {
            ManagedCustomerSession.fromPosExtras(mapOf("LanguageISO" to "es"), config)
        }
    }

    @Test fun `dominios documentales no confunden NIT de HioPos con TI de DIAN`() {
        val incoming = xml().replace(">9</CustomerField>", ">12</CustomerField>")
        val hio = ManagedCustomerSession.parse(incoming, config)
        val dian = ManagedCustomerSession.parse(incoming, config.copy(fiscalCodes = FiscalCodeDomain.DIAN))
        assertEquals("31", TipoDocumentoMapper.toDianCode(hio.parsed.data.fiscalDocumentType))
        assertEquals("12", TipoDocumentoMapper.toDianCode(dian.parsed.data.fiscalDocumentType))
        assertEquals("12", fields(hio.resultXml(hio.parsed.data, false))["fiscalDocumentType"])
    }

    @Test fun `XML hostil ambiguo o de otro contrato se rechaza`() {
        listOf("<!DOCTYPE CustomerResult [<!ENTITY x SYSTEM 'file:///private'>]>" + xml(),
            xml(extra = "<CustomerField Key=\"customerId\">456</CustomerField>"),
            "<Document/>", xml(extra = "<CustomerField Key=\"city\"><nested/></CustomerField>"),
            " ".repeat(ManagedCustomerSession.MAX_BYTES + 1)).forEach { bad ->
            assertThrows(Exception::class.java) { ManagedCustomerSession.parse(bad, config) }
        }
    }

    @Test fun `no se puede devolver un cliente sin validar datos`() {
        val session = ManagedCustomerSession.parse(xml(), config)
        assertThrows(IllegalArgumentException::class.java) { session.resultXml(session.parsed.data.copy(email = ""), true) }
    }
}
