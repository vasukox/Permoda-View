package com.permoda.facturacion.core.manage

import com.permoda.facturacion.core.hka.HkaConfig
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class ManageCustomerTransportTest {
    private val uri = ManageCustomerTransport.Resource("content", "icg.android.start.provider")

    @Test fun `cadena XML no abre otro recurso y cargas ambiguas fallan`() {
        assertEquals("<Customer/>", ManageCustomerTransport.read("<Customer/>", null, null) { error("No abrir") })
        assertThrows(IllegalArgumentException::class.java) {
            ManageCustomerTransport.read("<Customer/>", uri, "icg.android.start") { error("No abrir") }
        }
        assertThrows(IllegalArgumentException::class.java) {
            ManageCustomerTransport.read(null, null, "icg.android.start") { error("No abrir") }
        }
    }

    @Test fun `URI ajena path o llamador ausente no se abren`() {
        for (resource in listOf(ManageCustomerTransport.Resource("file", "icg.android.start.provider"),
            ManageCustomerTransport.Resource("content", "attacker.provider"))) {
            assertThrows(IllegalArgumentException::class.java) {
                ManageCustomerTransport.read(null, resource, "icg.android.start") { error("No abrir") }
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            ManageCustomerTransport.read(null, uri, null) { error("No abrir") }
        }
    }

    @Test fun `recurso autorizado se cierra y tiene limite en bytes`() {
        var closed = false
        val stream = object : java.io.ByteArrayInputStream("<Customer/>".toByteArray()) {
            override fun close() { closed = true; super.close() }
        }
        assertEquals("<Customer/>", ManageCustomerTransport.read(null, uri, "icg.android.start") { stream })
        assertTrue(closed)
        assertThrows(IllegalArgumentException::class.java) {
            ManageCustomerTransport.read(null, uri, "icg.android.start") { ByteArray(ManagedCustomerSession.MAX_BYTES + 1).inputStream() }
        }
        assertThrows(IllegalArgumentException::class.java) {
            ManageCustomerTransport.read("é".repeat(ManagedCustomerSession.MAX_BYTES), null, null) { null }
        }
    }

    @Test fun `fixtures publicados corresponden al contrato y deshabilitan HKA`() {
        val folder = listOf(File("../qa/fixtures"), File("qa/fixtures")).first { it.isDirectory }
        val params = File(folder, "parameters-test.xml").readText()
        val config = ManageCustomerConfig.fromParametersXml(params)
        assertFalse(HkaConfig.fromParametersXml(params).enabled)
        assertEquals(CustomerOperation.CREATE, ManagedCustomerSession.parse(File(folder, "manage-create.xml").readText(), config).operation)
        assertEquals(CustomerOperation.EDIT, ManagedCustomerSession.parse(File(folder, "manage-edit.xml").readText(), config).operation)
    }
}
