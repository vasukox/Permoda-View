package com.permoda.facturacion.core.scenario

import com.permoda.facturacion.core.customer.CustomerType
import com.permoda.facturacion.core.customer.CustomerTypeResolver
import com.permoda.facturacion.core.hka.CustomerMerger
import com.permoda.facturacion.core.hka.HkaConfig
import com.permoda.facturacion.core.hka.HkaResponse
import com.permoda.facturacion.core.model.CustomerData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Blinda el bug más peligroso que tuvo este flujo.
 *
 * El POS trae un cliente preseleccionado (digamos Juan). Si en la caja está
 * OTRA persona y teclea su propia cédula, todo lo de Juan tiene que
 * desaparecer — sobre todo el `customerId`.
 *
 * Con el `customerId` de Juan pegado a los datos de otra persona,
 * HioPosCloud **sobrescribe el registro de Juan**. No es un problema visual:
 * es corrupción del maestro de clientes.
 */
class IdentityLeakTest {

    /** Cliente que HioPosCloud traía preseleccionado. Juan es VIP. */
    private val juan = CustomerData(
        customerId = "1001",
        customerName = "Juan Perez Gomez",
        fiscalDocumentType = "13",
        fiscalId = "1011086580",
        email = "juan.perez@empresa.com",
        phone = "3111234567",
        countryIso2 = "CO",
        customerTypeId = 1,
        extraFieldsXml = "<CustomerField Key=\"city\">Bogota</CustomerField>"
    )

    /**
     * Réplica de lo que hace la app al detectar que es otra persona.
     *
     * ⚠️ Es una **copia** de `ValidationPresentation.forgetPreviousIdentity`, no
     * la función real (vive dentro de una `Presentation` de Android y no se puede
     * instanciar en la JVM). Esa duplicación ya dejó pasar un leak: cuando se
     * agregó `customerTypeId` al modelo, la función real no lo limpiaba y esta
     * réplica tampoco, así que el test seguía verde. **Si agregás un campo de
     * identidad a `CustomerData`, hay que limpiarlo en los dos lados.**
     */
    private fun forgetPreviousIdentity(
        incoming: CustomerData,
        tipo: String,
        numero: String
    ) = incoming.copy(
        customerId = null,
        customerName = "",
        email = "",
        phone = "",
        customerTypeId = null,
        fiscalDocumentType = tipo,
        fiscalId = numero
    )

    @Test
    fun `otra persona no hereda el customerId del cliente preseleccionado`() {
        val otra = forgetPreviousIdentity(juan, "13", "9999999999")
        assertNull(
            "el customerId de Juan haría que el POS sobrescriba su registro",
            otra.customerId
        )
    }

    @Test
    fun `otra persona no hereda nombre, correo ni telefono`() {
        val otra = forgetPreviousIdentity(juan, "13", "9999999999")
        assertEquals("", otra.customerName)
        assertEquals("", otra.email)
        assertEquals("", otra.phone)
    }

    @Test
    fun `otra persona no hereda el tipo de cliente`() {
        // El tipo también es identidad. Sin esto, quien está en la caja heredaba
        // el VIP (o el Empleado) del cliente preseleccionado — y desde que el
        // tipo se escribe en el `ModifyDocumentResult`, eso no queda en pantalla:
        // queda en el maestro de HioPos.
        val otra = forgetPreviousIdentity(juan, "13", "9999999999")
        assertNull("el VIP de Juan no es de esta persona", otra.customerTypeId)
        assertEquals(
            "y sin tipo entrante, la base es Estándar",
            CustomerType.ESTANDAR,
            CustomerTypeResolver.resolve(
                incomingTypeId = otra.customerTypeId,
                cashierEmployeeChoice = null,
                loyaltyOptIn = false,
                isConsumidorFinal = false
            )
        )
    }

    @Test
    fun `otra persona conserva su propio documento`() {
        val otra = forgetPreviousIdentity(juan, "31", "9999999999")
        assertEquals("31", otra.fiscalDocumentType)
        assertEquals("9999999999", otra.fiscalId)
    }

    @Test
    fun `los campos operativos del documento en curso se conservan`() {
        // La ciudad o el vendedor asignado son del documento de venta, no del
        // cliente: perderlos rompería otras cosas del POS.
        val otra = forgetPreviousIdentity(juan, "13", "9999999999")
        assertTrue(otra.extraFieldsXml.contains("city"))
        assertEquals("CO", otra.countryIso2)
    }

    @Test
    fun `el escenario de otra persona nunca es EDIT ni COMPLETE_EXISTING`() {
        val scenario = CustomerScenario.afterDocument(juan, "9999999999")
        assertTrue(
            "un documento ajeno no puede resolverse como el cliente del POS",
            scenario == CustomerScenario.CREATE_MANUAL
        )
    }

    @Test
    fun `la DIAN no puede resucitar el nombre del cliente anterior`() {
        // Base limpia + DIAN sin datos → el nombre queda vacío, NO vuelve Juan.
        val limpia = forgetPreviousIdentity(juan, "13", "9999999999")
        val merge = CustomerMerger.merge(
            local = limpia,
            hka = null,
            fallbackMode = HkaConfig.FallbackMode.HIBRIDO,
            hkaError = HkaResponse.NotFound("no existe")
        )
        assertEquals("", merge.customer.customerName)
        assertNull(merge.customer.customerId)
    }

    @Test
    fun `cuando la DIAN responde manda el nombre de la DIAN`() {
        val limpia = forgetPreviousIdentity(juan, "13", "9999999999")
        val merge = CustomerMerger.merge(
            local = limpia,
            hka = HkaResponse.Success("MARIA LOPEZ", "maria@mail.com"),
            fallbackMode = HkaConfig.FallbackMode.HIBRIDO
        )
        assertEquals("MARIA LOPEZ", merge.customer.customerName)
        assertEquals("maria@mail.com", merge.customer.email)
        assertNull(merge.customer.customerId)
    }

    @Test
    fun `el cliente que SI es el del POS conserva su customerId`() {
        // El otro lado de la moneda: si el documento coincide, no se borra nada.
        assertEquals(CustomerScenario.EDIT, CustomerScenario.afterDocument(juan, "1011086580"))
        assertEquals("1001", juan.customerId)
    }
}
