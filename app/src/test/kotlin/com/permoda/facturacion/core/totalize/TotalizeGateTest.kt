package com.permoda.facturacion.core.totalize

import com.permoda.facturacion.core.validation.CustomerValidator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TotalizeGateTest {

    private fun documento(
        name: String = "CARLOS EDUARDO TARAZONA JAIME",
        fiscalId: String = "95656565",
        email: String = "carlos@empresa.com",
        phone: String = "3111234567",
        docType: String = "13"
    ) = """
        <?xml version="1.0" encoding="UTF-8"?>
        <Document>
          <Header><HeaderField Key="PosId">3</HeaderField></Header>
          <Customer>
            <CustomerField Key="CustomerId">1001</CustomerField>
            <CustomerField Key="Name">$name</CustomerField>
            <CustomerField Key="FiscalIdDocType">$docType</CustomerField>
            <CustomerField Key="FiscalId">$fiscalId</CustomerField>
            <CustomerField Key="Email">$email</CustomerField>
            <CustomerField Key="Phone">$phone</CustomerField>
          </Customer>
          <Lines/>
        </Document>
    """.trimIndent()

    @Test
    fun `cliente correcto deja totalizar`() {
        assertTrue(TotalizeGate.decide("TOTALIZE_SALE", documento()) is TotalizeGate.Decision.Allow)
    }

    @Test
    fun `cliente con caracteres especiales bloquea con mensaje claro`() {
        val decision = TotalizeGate.decide("TOTALIZE_SALE", documento(name = "CARLOS TARAZONA @#", email = "")) as TotalizeGate.Decision.Block
        assertEquals(setOf(CustomerValidator.Field.NAME, CustomerValidator.Field.EMAIL), decision.fields.toSet())
        assertTrue(decision.message.startsWith("No se puede facturar"))
        assertTrue(decision.message, decision.message.contains("• Nombre y apellido: tiene caracteres especiales no permitidos (@ #)"))
        assertTrue(decision.message.contains("• Correo: está vacío"))
        assertTrue(decision.message.contains("vuelva a totalizar"))
    }

    @Test
    fun `tambien aplica despues de mix and match`() {
        assertTrue(TotalizeGate.decide("TOTALIZE_SALE_AFTER_MIXANDMATCH", documento(phone = "123")) is TotalizeGate.Decision.Block)
    }

    @Test
    fun `otros eventos no se validan`() {
        listOf(null, "PRINT", "CUSTOMER_SELECTION", "SET_SALE_ON_HOLD").forEach { evento ->
            assertTrue(TotalizeGate.decide(evento, documento(name = "@@@")) is TotalizeGate.Decision.Allow)
        }
    }

    @Test
    fun `consumidor final y venta sin cliente dejan seguir`() {
        listOf(
            documento(name = "Consumidor Final", fiscalId = "222222222222", email = "", phone = ""),
            documento(name = "CONSUMIDOR FINAL", fiscalId = "000000000", email = "", phone = ""),
            documento(name = "", fiscalId = "", email = "", phone = ""),
            "<Document><Header/></Document>"
        ).forEach { xml ->
            assertTrue(TotalizeGate.decide("TOTALIZE_SALE", xml) is TotalizeGate.Decision.Allow)
        }
    }

    @Test
    fun `errores tecnicos no detienen la caja`() {
        listOf(null, "", "esto no es xml", "<Document><Customer>").forEach { xml ->
            assertTrue("'$xml'", TotalizeGate.decide("TOTALIZE_SALE", xml) is TotalizeGate.Decision.Allow)
        }
    }

    @Test
    fun `el motivo de continuar no lleva datos personales`() {
        val allow = TotalizeGate.decide("TOTALIZE_SALE", documento()) as TotalizeGate.Decision.Allow
        assertTrue(!allow.reason.contains("95656565") && !allow.reason.contains("CARLOS"))
    }
}
