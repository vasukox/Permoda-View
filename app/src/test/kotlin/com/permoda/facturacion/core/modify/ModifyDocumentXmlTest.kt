package com.permoda.facturacion.core.modify

import com.permoda.facturacion.core.model.CustomerData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * HU-HIOPOS · Tests del camino `MODIFY_DOCUMENT`, que es por donde entra la
 * selección de cliente en el POS real (`TriggerEvent = CUSTOMER_SELECTION`).
 *
 * Corren sin Android: [ModifyDocumentXml] usa el DOM estándar a propósito.
 */
class ModifyDocumentXmlTest {

    // ------------------------------------------------------------------
    // Entrada: <Document> → CustomerData
    // ------------------------------------------------------------------

    private val documentoConCliente = """
        <?xml version="1.0" encoding="UTF-8"?>
        <Document>
          <Header>
            <HeaderField Key="PosId">3</HeaderField>
            <HeaderField Key="SplitPositiveAndNegativeDocs">true</HeaderField>
          </Header>
          <Customer>
            <CustomerField Key="CustomerId">1001</CustomerField>
            <CustomerField Key="Name">CARLOS EDUARDO TARAZONA JAIME</CustomerField>
            <CustomerField Key="FiscalIdDocType">13</CustomerField>
            <CustomerField Key="FiscalId">95656565</CustomerField>
            <CustomerField Key="Email">carlos@empresa.com</CustomerField>
            <CustomerField Key="Phone">3111234567</CustomerField>
            <CustomerField Key="CountryIso2">CO</CustomerField>
          </Customer>
        </Document>
    """.trimIndent()

    @Test
    fun `extrae el cliente del documento`() {
        val c = ModifyDocumentXml.parseDocumentCustomer(documentoConCliente)
        assertEquals("1001", c.customerId)
        assertEquals("CARLOS EDUARDO TARAZONA JAIME", c.customerName)
        assertEquals("13", c.fiscalDocumentType)
        assertEquals("95656565", c.fiscalId)
        assertEquals("carlos@empresa.com", c.email)
        assertEquals("3111234567", c.phone)
        assertEquals("CO", c.countryIso2)
    }

    @Test
    fun `un documento sin Customer no revienta`() {
        // Escenario normal: el cajero pulsa la lupita justamente porque la venta
        // todavía no tiene cliente. Devolver un CustomerData en blanco permite
        // que el flujo siga hacia la captura manual.
        val sinCliente = """
            <?xml version="1.0" encoding="UTF-8"?>
            <Document><Header><HeaderField Key="PosId">3</HeaderField></Header></Document>
        """.trimIndent()

        val c = ModifyDocumentXml.parseDocumentCustomer(sinCliente)
        assertNull(c.customerId)
        assertEquals("", c.customerName)
        assertNull(c.fiscalId)
    }

    @Test
    fun `un Customer con campos vacios se trata como ausente`() {
        val vacios = """
            <Document><Customer>
              <CustomerField Key="CustomerId"></CustomerField>
              <CustomerField Key="Name">   </CustomerField>
              <CustomerField Key="FiscalId"/>
            </Customer></Document>
        """.trimIndent()

        val c = ModifyDocumentXml.parseDocumentCustomer(vacios)
        assertNull("una cadena vacía no es un id", c.customerId)
        assertEquals("", c.customerName)
        assertNull(c.fiscalId)
    }

    @Test
    fun `encuentra Customer aunque este anidado mas profundo`() {
        // El contrato no fija el nivel exacto; anclarlo a una ruta rígida haría
        // frágil el parser ante cualquier cambio de estructura de ICG.
        val anidado = """
            <Document><Extra><Wrapper><Customer>
              <CustomerField Key="Name">Juan Perez</CustomerField>
            </Customer></Wrapper></Extra></Document>
        """.trimIndent()

        assertEquals("Juan Perez", ModifyDocumentXml.parseDocumentCustomer(anidado).customerName)
    }

    // ------------------------------------------------------------------
    // Salida: CustomerData → <ModifyDocumentResult>
    // ------------------------------------------------------------------

    @Test
    fun `camino EDIT cuando hay customerId lleva Header con el id Y bloque Customer con datos`() {
        // 13-08-2026 11:35: el usuario reportó que las ediciones de
        // email/phone/doc-type NO se guardaban. La causa: este path sólo
        // enviaba Header con CustomerId, sin bloque <Customer> con los
        // datos nuevos. Re-habilitamos el bloque para que las ediciones
        // (incluido el doc type con código HioPos) sí persistan.
        val out = ModifyDocumentXml.writeModifyDocumentResult(
            CustomerData(
                customerId = "1001",
                customerName = "Juan Perez",
                fiscalId = "1011086580",
                fiscalDocumentType = "13",
                email = "juan@empresa.com",
                phone = "3111234567",
                countryIso2 = "CO"
            )
        )
        assertTrue("el camino EDIT debe llevar el CustomerId en Header", out.contains(
            "<HeaderField Key=\"CustomerId\">1001</HeaderField>"
        ))
        // EDIT también lleva el bloque <Customer> con los datos actualizados
        // (nombre, email, phone, country, doc type) para que las ediciones
        // persistan en el maestro de HioPos.
        assertTrue(
            "el camino EDIT debe llevar el bloque <Customer> con los datos",
            out.contains("<Customer>")
        )
        assertTrue(
            "el camino EDIT lleva los CustomerField de los datos tipeados",
            out.contains("<CustomerField")
        )
        assertTrue(
            "el camino EDIT lleva CustomerFiscalId con la cédula",
            out.contains("<CustomerField Key=\"CustomerFiscalId\">1011086580</CustomerField>")
        )
        // ⚠️ El tipo de documento SÍ va desde el 13-08-2026 16:13 (lo re-habilitamos
        // porque el cajero confirmó que sin él, el cliente quedaba sin tipo
        // visible en HioPosCloud). Va con código HioPos (no DIAN) — para CC
        // es 9 — y `CustomerFiscalIdDocType` ahora está en [ModifyDocumentXml.CUSTOMER_KEYS].
        assertTrue(
            "el camino EDIT debe llevar el tipo de documento (código HioPos)",
            out.contains("<CustomerField Key=\"CustomerFiscalIdDocType\">9</CustomerField>")
        )
    }

    @Test
    fun `camino CREATE cuando no hay customerId escribe Header vacio y Customer completa`() {
        // Cliente nuevo o sin id asignado: <Header></Header> + <Customer>.
        // La <Header> vacía es necesaria para que HioPosCloud evalúe la regla
        // "no customer in header" y cargue nuestra <Customer>.
        //
        // ⚠️ EL TEST QUE IMPORTA. No lo pases a camelCase: ya se hizo el
        // 12-08-2026 y dejó de crearse cualquier cliente.
        //
        // Misma terminal (C9H), mismo día, dos builds:
        //
        // PascalCase — qa/logs/venta4.txt, 10:13. El POS procesa y guarda:
        //   PvModifyDoc: <CustomerField Key="CustomerName">ANDRES  DIAZ</…
        //   System.out: URL: …/CloudCentral/customers/setCustomerFromAPI/
        //   (y sólo DESPUÉS revienta en saveDynamicCustomer, que es otro problema
        //   y no depende de nuestras llaves: va con CustomerId = -1)
        //
        // camelCase — qa/logs/venta-camelcase.txt, 13:08 y 13:10. Silencio:
        //   PvModifyDoc: <CustomerField Key="customerName">Pruebaaaaaa</…
        //   (ni setCustomerFromAPI, ni onCustomerSaved, ni error)
        //
        // El razonamiento que llevó a camelCase mezcló dos estructuras: tomó la
        // convención del <Document> ENTRANTE y la aplicó al ModifyDocumentResult
        // SALIENTE. Son contratos separados.
        val out = ModifyDocumentXml.writeModifyDocumentResult(
            CustomerData(
                customerId = null,
                customerName = "Juan Perez",
                fiscalDocumentType = "13",
                fiscalId = "1011086580",
                email = "juan@empresa.com",
                phone = "3111234567",
                countryIso2 = "CO"
            )
        )
        assertTrue("el camino CREATE lleva <Header></Header>", out.contains("<Header></Header>"))
        assertFalse(
            "el camino CREATE no debe llevar CustomerId en Header (forzaría EDIT)",
            out.contains("HeaderField")
        )
        assertTrue("el camino CREATE debe llevar <Customer>", out.contains("<Customer>"))
        // Las llaves de <Customer> en PascalCase.
        //
        // `SendDocumentByEmail` NO está en esta lista: dejó de enviarse el
        // 28-ago-2026 por decisión de negocio. Las dos de factura sí siguen.
        listOf(
            "CustomerName", "CustomerFiscalId", "CustomerEmail", "CustomerPhone",
            "CustomerCountryIso2", "GenerateInvoice", "GenerateElectronicInvoice"
        ).forEach { key ->
            assertTrue("falta la llave $key", out.contains("<CustomerField Key=\"$key\">"))
        }
        // ⚠️ SONDA RE-HABILITADA el 13-08-2026 16:13. El cajero confirmó que
        // sin el tipo de documento, el cliente quedaba sin tipo visible en
        // HioPosCloud. Va con código HioPos (no DIAN): para CC = 9. Está en
        // [ModifyDocumentXml.CUSTOMER_KEYS].
        assertTrue(
            "el camino CREATE debe llevar el tipo de documento (código HioPos)",
            out.contains("<CustomerField Key=\"CustomerFiscalIdDocType\">9</CustomerField>")
        )
        // Ni una sola llave camelCase: el POS no las reconoce, no intenta
        // guardar, y no protesta. Silencio total.
        listOf(
            "customerName", "fiscalId", "email", "phone", "countryIso2",
            "fiscalDocumentType", "customerId"
        ).forEach { key ->
            assertTrue(
                "camelCase no debe aparecer en <Customer>: $key",
                !out.contains("<CustomerField Key=\"$key\">")
            )
        }
    }

    @Test
    fun `customerId centinelas 0, -1 y vacio se tratan como ausentes y van al camino CREATE`() {
        // El terminal manda 0 (venta sin cliente) y -1 (id provisional). También
        // contemplamos el caso de un id sólo con espacios. Hay que descartarlos
        // para NO entrar al camino EDIT con un id inválido.
        listOf("0", "-1", "  ", "").forEach { centinela ->
            val out = ModifyDocumentXml.writeModifyDocumentResult(
                CustomerData(
                    customerId = centinela,
                    customerName = "X",
                    fiscalId = "1011086580"
                )
            )
            assertTrue(
                "customerId='$centinela' debe caer al camino CREATE (Header vacía)",
                out.contains("<Header></Header>")
            )
            assertFalse(
                "customerId='$centinela' NO debe terminar en <HeaderField>: ruta inválida",
                out.contains("HeaderField")
            )
        }
    }

    @Test
    fun `NUNCA se escribe una llave fuera de la lista de la entidad Customer`() {
        // Cualquier llave desconocida hace que HioPosCloud la trate como campo
        // dinámico y aborte el guardado. Este test es el candado — corre sobre
        // el camino CREATE porque el EDIT no emite CustomerField.
        val out = ModifyDocumentXml.writeModifyDocumentResult(
            CustomerData(
                customerId = null,
                customerName = "Juan Perez",
                fiscalId = "1011086580",
                fiscalDocumentType = "13",
                email = "juan@empresa.com",
                phone = "3111234567",
                countryIso2 = "CO"
            ),
            invoicing = true
        )
        // Sólo las de <CustomerField>: los <HeaderField> son otra entidad, con su
        // propia convención (PascalCase) y su propia lista.
        val escritas = Regex("<CustomerField Key=\"([^\"]+)\"").findAll(out)
            .map { it.groupValues[1] }
            .toSet()
        val desconocidas = escritas - ModifyDocumentXml.CUSTOMER_KEYS
        assertTrue(
            "Llaves que HioPosCloud no reconoce: $desconocidas. Las tratará como " +
                "campos dinámicos y abortará el guardado del cliente.",
            desconocidas.isEmpty()
        )
    }

    @Test
    fun `los campos vacios se omiten en vez de mandarse en blanco`() {
        // Una cadena vacía podría pisar información que el POS ya tenía.
        val out = ModifyDocumentXml.writeModifyDocumentResult(
            CustomerData(customerName = "Juan Perez", email = null, phone = "")
        )
        assertTrue(!out.contains("Key=\"CustomerEmail\""))
        assertTrue(!out.contains("Key=\"CustomerPhone\""))
    }

    @Test
    fun `countryIso2 cae a CO cuando el documento no lo trae`() {
        val out = ModifyDocumentXml.writeModifyDocumentResult(CustomerData(customerName = "X"))
        assertTrue(out.contains("<CustomerField Key=\"CustomerCountryIso2\">CO</CustomerField>"))
    }

    @Test
    fun `countryIso2 respeta el pais que traia el documento`() {
        val out = ModifyDocumentXml.writeModifyDocumentResult(
            CustomerData(customerName = "X", countryIso2 = "US")
        )
        assertTrue(out.contains("<CustomerField Key=\"CustomerCountryIso2\">US</CustomerField>"))
        assertEquals(1, Regex("CustomerField Key=\"CustomerCountryIso2\"").findAll(out).count())
    }

    @Test
    fun `el correo del cliente viaja siempre que exista`() {
        val out = ModifyDocumentXml.writeModifyDocumentResult(
            CustomerData(customerName = "Juan Perez", email = "juan@empresa.com")
        )
        assertTrue(
            out.contains("<CustomerField Key=\"CustomerEmail\">juan@empresa.com</CustomerField>")
        )
    }

    @Test
    fun `las dos banderas de factura van en true y el envio por correo NO se envia`() {
        // Estado final tras dos vueltas de esta decisión. Lo que fija el test:
        //
        //  · `GenerateInvoice` y `GenerateElectronicInvoice` → SIEMPRE `true`,
        //    nunca derivadas de `IsInvoice`. Regresión del 12-08-2026:
        //      13:43 → true  … el cliente aparece en la pantalla de facturación
        //      13:56 → false … el cliente NO aparece
        //    `IsInvoice` es propiedad de LA VENTA; estas son DEL CLIENTE.
        //
        //  · `SendDocumentByEmail` → NUNCA. Decisión de negocio del 28-ago-2026.
        //    Es el único de los tres que ordena una acción ("mandá esto por
        //    correo") en vez de describir una propiedad, y es el que negocio no
        //    quiere activar en los clientes.
        //
        // El 27-ago se habían quitado las tres; el 28 volvieron las dos primeras
        // porque sin ellas el cliente quedaba sin marcar como facturable
        // electrónicamente y el módulo fiscal pedía un correo al consolidar.
        listOf(true, false).forEach { invoicing ->
            val out = ModifyDocumentXml.writeModifyDocumentResult(
                CustomerData(customerName = "X", email = "x@y.com"), invoicing = invoicing
            )
            assertTrue(
                "con invoicing=$invoicing GenerateInvoice debe seguir en true",
                out.contains("<CustomerField Key=\"GenerateInvoice\">true</CustomerField>")
            )
            assertTrue(
                out.contains("<CustomerField Key=\"GenerateElectronicInvoice\">true</CustomerField>")
            )
            assertTrue(
                "SendDocumentByEmail no debe enviarse nunca, ni con correo presente",
                !out.contains("Key=\"SendDocumentByEmail\"")
            )
        }
    }

    @Test
    fun `el cliente sigue viajando con sus datos aunque no se marquen banderas`() {
        // Blindaje del cambio anterior: quitar las banderas no debe haberse
        // llevado nada más. El `<Customer>` tiene que seguir completo.
        val out = ModifyDocumentXml.writeModifyDocumentResult(
            CustomerData(
                customerName = "Juan Perez Gomez",
                fiscalDocumentType = "13",
                fiscalId = "1011086580",
                email = "juan@empresa.com",
                phone = "3111234567",
                countryIso2 = "CO"
            )
        )
        listOf(
            "CustomerName", "CustomerFiscalId", "CustomerFiscalIdDocType",
            "CustomerEmail", "CustomerPhone", "CustomerCountryIso2"
        ).forEach { llave ->
            assertTrue("falta $llave", out.contains("Key=\"$llave\""))
        }
    }

    @Test
    fun `escapa los caracteres especiales del XML`() {
        val out = ModifyDocumentXml.writeModifyDocumentResult(
            CustomerData(customerName = "Perez & Cia <\"S.A.\">")
        )
        assertTrue(out.contains("Perez &amp; Cia &lt;&quot;S.A.&quot;&gt;"))
        assertTrue("no debe quedar un < sin escapar en el valor", !out.contains("Cia <"))
    }

    @Test
    fun `lee el customerId camelCase que manda el terminal real`() {
        // Este es el XML literal que HioPosCloud escribió en el terminal C9H.
        // Antes sólo se leía `CustomerId` en PascalCase, así que el id que el POS
        // sí envía nunca se leía y todo cliente existente se trataba como nuevo.
        val real = "<Document><Seller><SellerField Key=\"Email\"/></Seller>" +
            "<Customer><CustomerField Key=\"customerId\">4521</CustomerField></Customer>" +
            "<Provider><ProviderField Key=\"providerId\">0</ProviderField></Provider></Document>"

        assertEquals("4521", ModifyDocumentXml.parseDocumentCustomer(real).customerId)
    }

    @Test
    fun `sigue leyendo PascalCase por si ICG unifica la convencion`() {
        assertEquals("1001", ModifyDocumentXml.parseDocumentCustomer(documentoConCliente).customerId)
    }

    @Test
    fun `el customerId 0 del terminal significa venta SIN cliente`() {
        // ⚠️ El <Document> de una venta sin cliente NO omite la llave: manda
        // `customerId = 0`. Es el XML literal del terminal C9H.
        //
        // Sin filtrarlo, "0" pasa el isNotBlank() y viaja como un id real: el
        // módulo cree que la venta ya tiene cliente del maestro y trata cada
        // creación como una edición del cliente número cero. Y `-1` es el id
        // provisional que usa el POS mientras el cliente no tiene registro.
        val sinCliente = "<Document><Customer>" +
            "<CustomerField Key=\"customerId\">0</CustomerField></Customer>" +
            "<Provider><ProviderField Key=\"providerId\">0</ProviderField></Provider></Document>"
        assertNull(
            "0 es el centinela de 'sin cliente', no un id",
            ModifyDocumentXml.parseDocumentCustomer(sinCliente).customerId
        )

        val provisional = "<Document><Customer>" +
            "<CustomerField Key=\"customerId\">-1</CustomerField></Customer></Document>"
        assertNull(ModifyDocumentXml.parseDocumentCustomer(provisional).customerId)
    }

    @Test
    fun `round-trip documento con customerId cae al camino EDIT con datos`() {
        val entrada = ModifyDocumentXml.parseDocumentCustomer(documentoConCliente)
        val salida = ModifyDocumentXml.writeModifyDocumentResult(entrada, invoicing = true)

        // El documento trae customerId="1001" → el round-trip cae al camino
        // EDIT: Header CON el id Y bloque <Customer> con los datos (nombre,
        // email, doc type, etc.). Esto es lo que permite que las ediciones
        // persistan en el maestro de HioPos.
        assertTrue(
            "EDIT lleva el CustomerId en Header",
            salida.contains("<HeaderField Key=\"CustomerId\">1001</HeaderField>")
        )
        assertTrue(
            "EDIT también lleva el bloque <Customer> con los datos",
            salida.contains("<Customer>")
        )
        assertTrue(
            "EDIT lleva CustomerField con el nombre del documento",
            salida.contains("<CustomerField Key=\"CustomerName\">CARLOS EDUARDO TARAZONA JAIME</CustomerField>")
        )
    }

    @Test
    fun `round-trip documento sin customerId cae al camino CREATE`() {
        // Documento con customerId=null → el round-trip cae al camino CREATE:
        // <Header></Header> + <Customer> con los datos.
        val entrada = ModifyDocumentXml.parseDocumentCustomer(
            "<Document><Customer>" +
                "<CustomerField Key=\"customerId\">0</CustomerField>" +
                "<CustomerField Key=\"Name\">CARLOS</CustomerField>" +
                "<CustomerField Key=\"FiscalId\">95656565</CustomerField>" +
                "<CustomerField Key=\"Email\">carlos@empresa.com</CustomerField>" +
                "<CustomerField Key=\"CountryIso2\">CO</CustomerField>" +
                "</Customer></Document>"
        )
        assertNull(entrada.customerId)

        val salida = ModifyDocumentXml.writeModifyDocumentResult(entrada, invoicing = true)
        assertTrue("CREATE lleva <Header></Header>", salida.contains("<Header></Header>"))
        assertFalse("CREATE no lleva <HeaderField>", salida.contains("HeaderField"))
        assertTrue("CREATE lleva <Customer>", salida.contains("<Customer>"))
        assertTrue(
            salida.contains("<CustomerField Key=\"CustomerName\">CARLOS</CustomerField>")
        )
        assertTrue(
            salida.contains("<CustomerField Key=\"CustomerFiscalId\">95656565</CustomerField>")
        )
        assertTrue(
            salida.contains("<CustomerField Key=\"CustomerEmail\">carlos@empresa.com</CustomerField>")
        )
        assertTrue(
            salida.contains("<CustomerField Key=\"CustomerCountryIso2\">CO</CustomerField>")
        )
    }

    @Test
    fun `un XML gigante se rechaza en vez de agotar la memoria del terminal`() {
        val enorme = "<Document><Customer>" +
            "<CustomerField Key=\"Name\">" + "x".repeat(3 * 1024 * 1024) + "</CustomerField>" +
            "</Customer></Document>"
        val e = runCatching { ModifyDocumentXml.parseDocumentCustomer(enorme) }.exceptionOrNull()
        assertTrue("debía rechazarse", e is IllegalArgumentException)
    }

    @Test
    fun `legacy lee el tipo pero no lo confunde con naturaleza fiscal al devolver`() {
        // Cuando HioPos trae un cliente ya con tipo asignado (vía lupita), el
        // <Customer> del <Document> lleva `customerTypeId`. Tenemos que leerlo
        // para que el XML de salida lo respete. id=0 (Estándar) se parsea como 0
        // y `appendCustomerData` lo omite del XML porque equivale al default —
        // el POS lo asigna implícitamente.
        //
        // Sólo los tres tipos que usa Permoda: ver el KDoc de `CustomerType`.
        listOf(0, 1, 2).forEach { typeId ->
            val doc = "<Document><Customer>" +
                "<CustomerField Key=\"customerId\">1500122</CustomerField>" +
                "<CustomerField Key=\"fiscalId\">1011086580</CustomerField>" +
                "<CustomerField Key=\"customerTypeId\">$typeId</CustomerField>" +
                "</Customer></Document>"
            val parsed = ModifyDocumentXml.parseDocumentCustomer(doc)
            assertEquals(
                "id=$typeId debe sobrevivir en el round-trip",
                typeId,
                parsed.customerTypeId
            )
            // Round-trip: el id sale en el XML de salida si y sólo si != 0.
            val out = ModifyDocumentXml.writeModifyDocumentResult(parsed, invoicing = true)
            val expected = false
            assertEquals(
                "CustomerTypeId=$typeId en XML de salida",
                expected,
                out.contains("CustomerTypeId")
            )
        }
    }

    @Test
    fun `legacy no emite categoria comercial por un campo fiscal`() {
        // HioPos no tiene que hacer un lookup extra para saber cómo se llama el
        // tipo que le mandamos. Es el par que espera su parser.
        val doc = "<Document><Customer>" +
            "<CustomerField Key=\"customerTypeId\">1</CustomerField></Customer></Document>"
        val out = ModifyDocumentXml.writeModifyDocumentResult(
            ModifyDocumentXml.parseDocumentCustomer(doc).copy(customerName = "X")
        )
        assertFalse(out.contains("CustomerTypeId"))
        assertFalse(out.contains("CustomerTypeName"))
    }

    @Test
    fun `customerTypeId fuera de los tres tipos de Permoda se descarta`() {
        // `99` es basura (dato viejo de un módulo retirado). `3`, `4` y `5` son
        // tipos REALES del maestro de HioPos —Atención especial, CLIENTE TIENDA,
        // CLIENTE ECOMMERCE— que PermodaView no maneja por decisión de negocio
        // del 19-ago-2026, así que también caen a "sin tipo".
        //
        // ⚠️ Consecuencia aceptada, no descuido: un cliente con esos tipos se
        // normaliza (y puede terminar sobrescrito a VIP si acepta Koaj). Si algún
        // día hay que preservarlos, hay que agregarlos a `CustomerType` y este
        // test es el que avisa.
        listOf(3, 4, 5, 99).forEach { id ->
            val doc = "<Document><Customer>" +
                "<CustomerField Key=\"customerId\">1500122</CustomerField>" +
                "<CustomerField Key=\"customerTypeId\">$id</CustomerField>" +
                "</Customer></Document>"
            assertNull(
                "id=$id debe leerse como sin tipo",
                ModifyDocumentXml.parseDocumentCustomer(doc).customerTypeId
            )
        }
    }

    @Test
    fun `customerTypeId del Document tambien soporta PascalCase`() {
        // El bytecode de HioPosDocumentActivity lee ambas convenciones
        // (`customerTypeId` y `CustomerTypeId`). Nuestro parser tiene que
        // matchear lo mismo para no perder el tipo según qué versión del POS.
        val camel = "<Document><Customer>" +
            "<CustomerField Key=\"customerTypeId\">2</CustomerField></Customer></Document>"
        val pascal = "<Document><Customer>" +
            "<CustomerField Key=\"CustomerTypeId\">2</CustomerField></Customer></Document>"
        assertEquals(2, ModifyDocumentXml.parseDocumentCustomer(camel).customerTypeId)
        assertEquals(2, ModifyDocumentXml.parseDocumentCustomer(pascal).customerTypeId)
    }

    @Test
    fun `el round-trip por CustomerResult perdia el tipo de cliente`() {
        // Regresión real. `CustomerSelectionActivity` traducía el <Document> a un
        // `CustomerResult` y lo volvía a parsear "para unificar el flujo". Ni
        // `Xml.writeCustomerResult` escribe `customerTypeId` ni
        // `Xml.parseCustomer` lo lee, así que el tipo se leía bien del POS y se
        // borraba milisegundos después. El round-trip se eliminó; este test
        // documenta por qué no se puede reintroducir.
        val doc = "<Document><Customer>" +
            "<CustomerField Key=\"customerId\">1500122</CustomerField>" +
            "<CustomerField Key=\"customerTypeId\">2</CustomerField>" +
            "</Customer></Document>"
        val delPos = ModifyDocumentXml.parseDocumentCustomer(doc)
        assertEquals(2, delPos.customerTypeId)

        val trasRoundTrip = com.permoda.facturacion.core.Xml.parseCustomer(
            com.permoda.facturacion.core.Xml.writeCustomerResult(delPos, extraFieldsXml = "")
        ).data
        assertNull(
            "si esto deja de ser null, `Xml` aprendió la llave y el round-trip " +
                "volvió a ser seguro — revisar CustomerSelectionActivity",
            trasRoundTrip.customerTypeId
        )
    }
}
