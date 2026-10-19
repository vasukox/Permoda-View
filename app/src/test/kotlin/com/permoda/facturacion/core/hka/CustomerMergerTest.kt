package com.permoda.facturacion.core.hka

import com.permoda.facturacion.core.model.CustomerData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomerMergerTest {

    private val baseLocal = CustomerData(
        customerId = "123",
        customerName = "Juan Perez",
        fiscalDocumentType = "13",
        fiscalId = "12345678",
        email = "juan.perez@gmail.com",
        phone = "+57 311 1234567",
        extraFieldsXml = "<CustomerField Key=\"address\">Calle 123</CustomerField>"
    )

    @Test
    fun `merge con DIAN success reemplaza nombre y email`() {
        val hka = HkaResponse.Success(
            nombreReceptor = "JUAN PEREZ GOMEZ",
            correoReceptor = "juan.perez@outlook.com"
        )
        val result = CustomerMerger.merge(baseLocal, hka, HkaConfig.FallbackMode.HIBRIDO)

        assertEquals("JUAN PEREZ GOMEZ", result.customer.customerName)
        assertEquals("juan.perez@outlook.com", result.customer.email)
        assertEquals("+57 311 1234567", result.customer.phone) // local gana
        assertEquals(MergeSource.DIAN_OVERRIDE, result.source)
        assertNull(result.warning)
    }

    @Test
    fun `merge con DIAN success pero sin cambios reporta DIAN_CONFIRMED`() {
        val hka = HkaResponse.Success(
            nombreReceptor = "Juan Perez",      // mismo que local
            correoReceptor = "juan.perez@gmail.com"  // mismo que local
        )
        val result = CustomerMerger.merge(baseLocal, hka, HkaConfig.FallbackMode.HIBRIDO)

        assertEquals(MergeSource.DIAN_CONFIRMED, result.source)
        assertNull(result.warning)
    }

    @Test
    fun `merge con DIAN success con campos vacios no pisa los locales`() {
        val hka = HkaResponse.Success(
            nombreReceptor = "",   // vacío → no pisa
            correoReceptor = ""    // vacío → no pisa
        )
        val result = CustomerMerger.merge(baseLocal, hka, HkaConfig.FallbackMode.HIBRIDO)

        assertEquals("Juan Perez", result.customer.customerName)
        assertEquals("juan.perez@gmail.com", result.customer.email)
    }

    @Test
    fun `merge sin HKA devuelve LOCAL_ONLY`() {
        val result = CustomerMerger.merge(baseLocal, null, HkaConfig.FallbackMode.HIBRIDO)
        assertEquals(MergeSource.LOCAL_ONLY, result.source)
        assertEquals("Juan Perez", result.customer.customerName)
        assertNull(result.warning)
    }

    @Test
    fun `merge con NotFound devuelve DIAN_NOT_REGISTERED sin warning`() {
        val result = CustomerMerger.merge(
            local = baseLocal,
            hka = null,
            fallbackMode = HkaConfig.FallbackMode.CONSERVADOR,
            hkaError = HkaResponse.NotFound("No existe en la DIAN")
        )
        // 404 no es un fallo de la integración: es el escenario 3.
        // Ni siquiera en modo CONSERVADOR debe cortar el flujo.
        assertEquals(MergeSource.DIAN_NOT_REGISTERED, result.source)
        assertTrue(result.warning?.contains("manualmente") == true)
    }

    // ------------------------------------------------------------------
    // dianStatus — lo que la pantalla del cliente puede AFIRMAR.
    // Antes los tres casos caían en LOCAL_ONLY y la UI mostraba el mismo
    // "✓ Usuario validado en la DIAN" incluso con un 404.
    // ------------------------------------------------------------------

    @Test
    fun `dianStatus es VALIDATED cuando la DIAN respondio`() {
        val hka = HkaResponse.Success("JUAN PEREZ GOMEZ", "juan@outlook.com")
        val result = CustomerMerger.merge(baseLocal, hka, HkaConfig.FallbackMode.HIBRIDO)
        assertEquals(DianStatus.VALIDATED, result.dianStatus)
    }

    @Test
    fun `dianStatus es NOT_REGISTERED con 404 y nunca afirma validacion`() {
        val result = CustomerMerger.merge(
            local = baseLocal,
            hka = null,
            fallbackMode = HkaConfig.FallbackMode.HIBRIDO,
            hkaError = HkaResponse.NotFound("No registrado")
        )
        assertEquals(DianStatus.NOT_REGISTERED, result.dianStatus)
        assertFalse(result.dianStatus.isValidated)
    }

    @Test
    fun `dianStatus es UNVERIFIED cuando la DIAN no respondio`() {
        listOf(
            HkaResponse.Timeout(),
            HkaResponse.NetworkError("Sin red"),
            HkaResponse.ServerError("DIAN caída", 500)
        ).forEach { error ->
            val result = CustomerMerger.merge(
                local = baseLocal,
                hka = null,
                fallbackMode = HkaConfig.FallbackMode.HIBRIDO,
                hkaError = error
            )
            assertEquals(
                "esperaba UNVERIFIED para $error",
                DianStatus.UNVERIFIED,
                result.dianStatus
            )
        }
    }

    @Test
    fun `dianStatus es UNVERIFIED cuando no se consulto la DIAN`() {
        val result = CustomerMerger.merge(baseLocal, null, HkaConfig.FallbackMode.HIBRIDO)
        assertEquals(DianStatus.UNVERIFIED, result.dianStatus)
    }

    @Test
    fun `modo PERMISIVO con error devuelve LOCAL_FALLBACK sin warning`() {
        val result = CustomerMerger.merge(
            local = baseLocal,
            hka = null,
            fallbackMode = HkaConfig.FallbackMode.PERMISIVO,
            hkaError = HkaResponse.Timeout()
        )
        assertEquals(MergeSource.LOCAL_FALLBACK, result.source)
        assertNull(result.warning)
    }

    @Test
    fun `modo HIBRIDO con error devuelve LOCAL_FALLBACK_WARNING con warning`() {
        val result = CustomerMerger.merge(
            local = baseLocal,
            hka = null,
            fallbackMode = HkaConfig.FallbackMode.HIBRIDO,
            hkaError = HkaResponse.ServerError("DIAN caída", 500)
        )
        assertEquals(MergeSource.LOCAL_FALLBACK_WARNING, result.source)
        assertTrue(result.warning?.contains("DIAN") == true)
    }

    // ------------------------------------------------------------------
    // Regla de oro: la DIAN NUNCA bloquea la venta.
    // `CONSERVADOR` devolvía BLOCKED y la pantalla mostraba un error sin
    // salida; ahora se degrada a captura manual con aviso.
    // ------------------------------------------------------------------

    @Test
    fun `modo CONSERVADOR con error NO bloquea, avisa y deja continuar`() {
        val result = CustomerMerger.merge(
            local = baseLocal,
            hka = null,
            fallbackMode = HkaConfig.FallbackMode.CONSERVADOR,
            hkaError = HkaResponse.NetworkError("Sin red")
        )
        assertEquals(MergeSource.LOCAL_FALLBACK_WARNING, result.source)
        assertTrue(result.warning?.contains("manualmente") == true)
        // Los datos locales siguen disponibles para editar.
        assertEquals("Juan Perez", result.customer.customerName)
    }

    @Test
    fun `ningun fallo de DIAN produce un estado de bloqueo`() {
        val errores = listOf(
            HkaResponse.Timeout(),
            HkaResponse.NetworkError("Sin red"),
            HkaResponse.ServerError("500", 500),
            HkaResponse.ServerError("503", 503),
            HkaResponse.RateLimited(),
            HkaResponse.BadRequest("malformada"),
            HkaResponse.NotFound("no existe")
        )
        val modos = HkaConfig.FallbackMode.entries

        errores.forEach { error ->
            modos.forEach { modo ->
                val result = CustomerMerger.merge(
                    local = baseLocal,
                    hka = null,
                    fallbackMode = modo,
                    hkaError = error
                )
                // El cliente final siempre viene con los datos locales
                // disponibles: nunca hay un camino sin salida.
                assertEquals(
                    "perdió los datos locales con $error / $modo",
                    baseLocal.customerName,
                    result.customer.customerName
                )
                assertFalse(
                    "$error con $modo no debería afirmar validación",
                    result.source == MergeSource.DIAN_CONFIRMED ||
                        result.source == MergeSource.DIAN_OVERRIDE
                )
            }
        }
    }

    @Test
    fun `merge preserva extraFieldsXml del local`() {
        val hka = HkaResponse.Success(
            nombreReceptor = "OTRO NOMBRE",
            correoReceptor = "otro@mail.com"
        )
        val result = CustomerMerger.merge(baseLocal, hka, HkaConfig.FallbackMode.HIBRIDO)
        assertEquals(baseLocal.extraFieldsXml, result.customer.extraFieldsXml)
    }

    @Test
    fun `merge preserva campos operativos del local`() {
        val hka = HkaResponse.Success(
            nombreReceptor = "OTRO",
            correoReceptor = "otro@mail.com"
        )
        val result = CustomerMerger.merge(baseLocal, hka, HkaConfig.FallbackMode.HIBRIDO)
        assertEquals(baseLocal.fiscalDocumentType, result.customer.fiscalDocumentType)
        assertEquals(baseLocal.fiscalId, result.customer.fiscalId)
        assertEquals(baseLocal.phone, result.customer.phone)
        assertEquals(baseLocal.customerId, result.customer.customerId)
    }
}
