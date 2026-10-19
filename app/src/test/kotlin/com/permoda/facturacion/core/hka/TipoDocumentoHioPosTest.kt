package com.permoda.facturacion.core.hka

import com.permoda.facturacion.core.modify.ModifyDocumentXml
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * HU-HIOPOS · Tabla de tipos de documento **de HioPos**, que no es la de la DIAN.
 *
 * Los números los confirmó operaciones contra el terminal el 13-08-2026, y el
 * `<Customer>` en crudo del C9H (`qa/logs/eliminado-recrear.txt`) los corrobora:
 *
 * ```
 * <CustomerField Key="FiscalIdDocType">9</CustomerField>
 * <CustomerField Key="FiscalId">1011086580</CustomerField>   ← una cédula
 * ```
 *
 * Estos tests existen porque **los dos dominios numéricos se pisan** y confundirlos
 * no lanza ninguna excepción: simplemente tipifica mal al cliente, en silencio.
 */
class TipoDocumentoHioPosTest {

    /** Los 8 tipos confirmados: nombre completo, código HioPos, código DIAN. */
    private val confirmados = listOf(
        Triple("Registro civil", "7", "11"),
        Triple("Tarjeta de identidad", "8", "12"),
        Triple("Cédula de ciudadanía", "9", "13"),
        Triple("Cédula de extranjería", "11", "22"),
        Triple("NIT", "12", "31"),
        Triple("PEP", "47", "47"),
        Triple("PTP", "64", "48"),
        Triple("Pasaporte", "65", "41")
    )

    @Test
    fun `los 8 tipos confirmados van y vuelven sin perderse`() {
        confirmados.forEach { (fullName, hiopos, dian) ->
            assertEquals(
                "$fullName debe salir como HioPos $hiopos",
                hiopos,
                TipoDocumentoMapper.toHioPosCode(fullName)
            )
            assertEquals(
                "HioPos $hiopos debe leerse como $fullName",
                fullName,
                TipoDocumentoMapper.fromHioPosCode(hiopos)
            )
            assertEquals(
                "$fullName debe salir como DIAN $dian",
                dian,
                TipoDocumentoMapper.toDianCode(fullName)
            )
        }
    }

    @Test
    fun `los tres numeros que colisionan se leen como HioPos, no como DIAN`() {
        // ⚠️ EL TEST QUE IMPORTA. Estos tres valores existen en los dos dominios
        // con significados distintos:
        //
        //   11 → HioPos: cédula de extranjería  · DIAN: registro civil
        //   12 → HioPos: NIT                    · DIAN: tarjeta de identidad
        //   47 → HioPos: PEP                    · DIAN: PEP (coincide)
        //
        // El bug: `FiscalIdDocType` entrante se leía con `toFullName()`, o sea
        // como código DIAN. Un cliente con NIT se mostraba como "Tarjeta de
        // identidad" y uno con cédula de extranjería como "Registro civil". Si el
        // cliente confirmaba sin fijarse, el tipo equivocado se guardaba.
        assertEquals("Cédula de extranjería", TipoDocumentoMapper.fromHioPosCode("11"))
        assertEquals("NIT", TipoDocumentoMapper.fromHioPosCode("12"))
        assertEquals("PEP", TipoDocumentoMapper.fromHioPosCode("47"))

        // Y esto es lo que devolvía antes, para que quede constancia de la
        // diferencia: leerlos como DIAN da otra cosa.
        assertEquals("Registro civil", TipoDocumentoMapper.toFullName("11"))
        assertEquals("Tarjeta de identidad", TipoDocumentoMapper.toFullName("12"))
    }

    @Test
    fun `los numeros de HioPos que no son codigos DIAN validos ya no caen al default`() {
        // 7, 8, 9, 64 y 65 no existen como código DIAN, así que `toFullName`
        // devuelve null y el selector de la pantalla caía al primer tipo de la
        // lista — "Cédula de ciudadanía" — para TODOS ellos.
        listOf("7", "8", "9", "64", "65").forEach { hiopos ->
            assertNull("$hiopos no es código DIAN", TipoDocumentoMapper.toFullName(hiopos))
            org.junit.Assert.assertNotNull(
                "$hiopos sí es código HioPos y debe resolverse",
                TipoDocumentoMapper.fromHioPosCode(hiopos)
            )
        }
    }

    @Test
    fun `un tipo sin confirmar devuelve null en vez de un numero inventado`() {
        // Antes `TE`, `DE`, `NE` y `NUIP` tenían valores adivinados (10, 13, 66,
        // 14). `DE → 13` era el peor: 13 es el código DIAN de la cédula de
        // ciudadanía. No mandar el tipo es recuperable; mandarlo mal no.
        listOf(
            "Tarjeta de extranjería",
            "Documento de identificación extranjero",
            "NIT de otro país",
            "NUIP"
        ).forEach { fullName ->
            assertNull(
                "$fullName no está confirmado: no debe emitir código HioPos",
                TipoDocumentoMapper.toHioPosCode(fullName)
            )
        }
        // Y ninguno de esos números inventados debe resolver a nada.
        listOf("10", "66", "14").forEach {
            assertNull(it, TipoDocumentoMapper.fromHioPosCode(it))
        }
    }

    @Test
    fun `PTP es el nombre canonico y PPT sigue aceptandose`() {
        // Operaciones confirmó que HioPos lo llama "PTP". Se conserva "PPT" como
        // alias de entrada por si algo viejo lo manda.
        assertEquals("PTP", TipoDocumentoMapper.toFullName("PTP"))
        assertEquals("PTP", TipoDocumentoMapper.toFullName("PPT"))
        assertEquals("64", TipoDocumentoMapper.toHioPosCode("PPT"))
        org.junit.Assert.assertTrue(
            "el selector debe ofrecer PTP",
            TipoDocumentoMapper.supportedFullNames().contains("PTP")
        )
        org.junit.Assert.assertFalse(
            "el selector ya no debe ofrecer PPT",
            TipoDocumentoMapper.supportedFullNames().contains("PPT")
        )
    }

    // ------------------------------------------------------------------
    // El borde: el <Document> entrante
    // ------------------------------------------------------------------

    @Test
    fun `el FiscalIdDocType del documento se traduce al parsear`() {
        // Este es el <Customer> literal del terminal, recortado.
        val doc = "<Document><Customer>" +
            "<CustomerField Key=\"customerId\">1500122</CustomerField>" +
            "<CustomerField Key=\"FiscalIdDocType\">12</CustomerField>" +
            "<CustomerField Key=\"FiscalId\">900123456</CustomerField>" +
            "<CustomerField Key=\"Name\">EMPRESA SAS</CustomerField>" +
            "</Customer></Document>"

        val c = ModifyDocumentXml.parseDocumentCustomer(doc)
        assertEquals("1500122", c.customerId)
        assertEquals(
            "HioPos 12 es NIT; leerlo como DIAN daría 'Tarjeta de identidad'",
            "NIT",
            c.fiscalDocumentType
        )
    }

    @Test
    fun `un FiscalIdDocType desconocido se conserva crudo y no se pierde`() {
        val doc = "<Document><Customer>" +
            "<CustomerField Key=\"FiscalIdDocType\">999</CustomerField>" +
            "</Customer></Document>"

        assertEquals(
            "un valor no mapeable se conserva tal cual",
            "999",
            ModifyDocumentXml.parseDocumentCustomer(doc).fiscalDocumentType
        )
    }

    @Test
    fun `un documento sin tipo de documento sigue devolviendo null`() {
        val doc = "<Document><Customer>" +
            "<CustomerField Key=\"FiscalIdDocType\"/>" +
            "</Customer></Document>"

        assertNull(ModifyDocumentXml.parseDocumentCustomer(doc).fiscalDocumentType)
    }
}
