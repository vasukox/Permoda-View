package com.permoda.facturacion.core.scenario

import com.permoda.facturacion.core.hka.DianStatus
import com.permoda.facturacion.core.model.CustomerData
import com.permoda.facturacion.core.validation.CustomerValidator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * El escenario se resuelve **después** de que el cliente teclea su documento:
 * PermodaView es el único punto donde se captura, así que antes de eso no se
 * sabe de qué cliente se está hablando.
 */
class CustomerScenarioTest {

    /** Cliente que trajo el POS, con todo lo necesario para facturar. */
    private val delPos = CustomerData(
        customerId = "1001",
        customerName = "Juan Perez",
        fiscalDocumentType = "13",
        fiscalId = "1011086580",
        email = "juan.perez@gmail.com",
        phone = "3111234567"
    )

    /** Lo que manda el POS cuando no hay cliente seleccionado. */
    private val vacio = CustomerData(customerName = "")

    // ------------------------------------------------------------------
    // Escenario 1: es el cliente del POS y sus datos sirven
    // ------------------------------------------------------------------

    @Test
    fun `documento que coincide con el del POS y datos completos es EDIT`() {
        assertEquals(
            CustomerScenario.EDIT,
            CustomerScenario.afterDocument(delPos, "1011086580")
        )
    }

    @Test
    fun `el documento se compara ignorando espacios`() {
        assertEquals(
            CustomerScenario.EDIT,
            CustomerScenario.afterDocument(delPos, "  1011086580  ")
        )
    }

    // ------------------------------------------------------------------
    // Escenario 3: es el cliente del POS pero le faltan datos
    // ------------------------------------------------------------------

    @Test
    fun `mismo documento pero sin correo es COMPLETE_EXISTING`() {
        assertEquals(
            CustomerScenario.COMPLETE_EXISTING,
            CustomerScenario.afterDocument(delPos.copy(email = ""), "1011086580")
        )
    }

    @Test
    fun `mismo documento pero sin nombre completo es COMPLETE_EXISTING`() {
        assertEquals(
            CustomerScenario.COMPLETE_EXISTING,
            CustomerScenario.afterDocument(delPos.copy(customerName = "Juan"), "1011086580")
        )
    }

    @Test
    fun `relajar la regla del correo mueve el escenario 3 al 1`() {
        val sinCorreo = delPos.copy(email = "")
        assertEquals(
            CustomerScenario.COMPLETE_EXISTING,
            CustomerScenario.afterDocument(sinCorreo, "1011086580")
        )
        assertEquals(
            CustomerScenario.EDIT,
            CustomerScenario.afterDocument(
                sinCorreo,
                "1011086580",
                CustomerValidator.Rules(requireEmail = false)
            )
        )
    }

    // ------------------------------------------------------------------
    // Escenarios 2 y 4: nuevo para HioPosCloud
    // ------------------------------------------------------------------

    @Test
    fun `sin cliente del POS siempre es nuevo`() {
        assertEquals(
            CustomerScenario.CREATE_MANUAL,
            CustomerScenario.afterDocument(vacio, "1011086580")
        )
    }

    @Test
    fun `documento distinto al del POS es un cliente nuevo`() {
        // El cajero tenía preseleccionado a Juan, pero quien está en la caja
        // es otra persona. No podemos facturarle a Juan.
        assertEquals(
            CustomerScenario.CREATE_MANUAL,
            CustomerScenario.afterDocument(delPos, "9999999999")
        )
    }

    @Test
    fun `un cliente del POS sin customerId cuenta como nuevo`() {
        // Sin customerId no está en el maestro, aunque traiga datos.
        val sinId = delPos.copy(customerId = null)
        assertEquals(
            CustomerScenario.CREATE_MANUAL,
            CustomerScenario.afterDocument(sinId, "1011086580")
        )
    }

    @Test
    fun `customerId en blanco cuenta como nuevo`() {
        listOf("", "   ").forEach { blank ->
            assertEquals(
                "'$blank' no debería contar como existente",
                CustomerScenario.CREATE_MANUAL,
                CustomerScenario.afterDocument(delPos.copy(customerId = blank), "1011086580")
            )
        }
    }

    // ------------------------------------------------------------------
    // Comparación de documentos
    // ------------------------------------------------------------------

    @Test
    fun `sameDocument compara solo el numero`() {
        assertTrue(CustomerScenario.sameDocument("1011086580", "1011086580"))
        assertTrue(CustomerScenario.sameDocument(" 1011086580 ", "1011086580"))
        assertFalse(CustomerScenario.sameDocument("1011086580", "9999999999"))
    }

    @Test
    fun `sameDocument con vacios es false`() {
        assertFalse(CustomerScenario.sameDocument(null, "1011086580"))
        assertFalse(CustomerScenario.sameDocument("", "1011086580"))
        assertFalse(CustomerScenario.sameDocument("   ", "   "))
        assertFalse(CustomerScenario.sameDocument("1011086580", null))
    }

    @Test
    fun `un tipo de documento distinto NO rompe la coincidencia`() {
        // El número identifica a la persona; el tipo es metadato que se graba
        // mal a menudo. Exigir que coincida crearía clientes duplicados.
        val comoNit = delPos.copy(fiscalDocumentType = "31")
        assertEquals(
            CustomerScenario.EDIT,
            CustomerScenario.afterDocument(comoNit, "1011086580")
        )
    }

    // ------------------------------------------------------------------
    // Lo que dice la DIAN (sólo para clientes nuevos)
    // ------------------------------------------------------------------

    @Test
    fun `DIAN validada lleva a CREATE_FROM_DIAN`() {
        assertEquals(
            CustomerScenario.CREATE_FROM_DIAN,
            CustomerScenario.afterDian(DianStatus.VALIDATED)
        )
    }

    @Test
    fun `DIAN no registrada o caida llevan a CREATE_MANUAL`() {
        assertEquals(
            CustomerScenario.CREATE_MANUAL,
            CustomerScenario.afterDian(DianStatus.NOT_REGISTERED)
        )
        assertEquals(
            CustomerScenario.CREATE_MANUAL,
            CustomerScenario.afterDian(DianStatus.UNVERIFIED)
        )
    }

    @Test
    fun `ningun estado de DIAN produce un escenario sin salida`() {
        DianStatus.entries.forEach { status ->
            assertTrue(
                "$status debería permitir crear el cliente",
                CustomerScenario.afterDian(status).createsCustomer
            )
        }
    }

    // ------------------------------------------------------------------
    // Metadatos
    // ------------------------------------------------------------------

    @Test
    fun `solo los escenarios de creacion crean cliente`() {
        assertFalse(CustomerScenario.EDIT.createsCustomer)
        assertTrue(CustomerScenario.CREATE_FROM_DIAN.createsCustomer)
        assertFalse(CustomerScenario.COMPLETE_EXISTING.createsCustomer)
        assertTrue(CustomerScenario.CREATE_MANUAL.createsCustomer)
        assertFalse(CustomerScenario.CONSUMIDOR_FINAL.createsCustomer)
    }

    @Test
    fun `solo EDIT se salta la compuerta del vendedor`() {
        assertFalse(CustomerScenario.EDIT.needsVendorApproval)
        assertTrue(CustomerScenario.COMPLETE_EXISTING.needsVendorApproval)
        assertTrue(CustomerScenario.CREATE_FROM_DIAN.needsVendorApproval)
        assertTrue(CustomerScenario.CREATE_MANUAL.needsVendorApproval)
    }

    @Test
    fun `los actions de auditoria son los acordados con ICG`() {
        assertEquals("cliente_existente", CustomerScenario.EDIT.auditAction)
        assertEquals("cliente_creado_con_dian", CustomerScenario.CREATE_FROM_DIAN.auditAction)
        assertEquals("cliente_completado", CustomerScenario.COMPLETE_EXISTING.auditAction)
        assertEquals("cliente_creado_sin_dian", CustomerScenario.CREATE_MANUAL.auditAction)
        assertEquals("cliente_consumidor_final", CustomerScenario.CONSUMIDOR_FINAL.auditAction)
    }

    @Test
    fun `cada escenario tiene un action distinto y dentro del tope`() {
        val actions = CustomerScenario.entries.map { it.auditAction }
        assertEquals(actions.size, actions.toSet().size)
        assertTrue(actions.all { it.startsWith("cliente_") && it.length <= 50 })
    }
}
