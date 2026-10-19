package com.permoda.facturacion.core.hka

import io.mockk.*
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.*
import okhttp3.Call
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.io.InterruptedIOException

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class HkaClientTest {
    private val factory = mockk<Call.Factory>()
    private val call = mockk<Call>(relaxed = true)
    private val callback = slot<Callback>()
    private val request = slot<Request>()
    private val config = HkaConfig.defaults().copy(baseUrl = "https://hka.example.test")

    private fun prepare(): HkaClient {
        every { factory.newCall(capture(request)) } returns call
        every { call.enqueue(capture(callback)) } just Runs
        return HkaClient(config, factory)
    }

    @Test fun `cancelar el flujo cancela la llamada HTTP real`() = runTest {
        val client = prepare()
        val pending = async { client.consultaAdquiriente("13", "12345678") }
        runCurrent()
        verify(exactly = 1) { call.enqueue(any()) }
        pending.cancel()
        runCurrent()
        verify(exactly = 1) { call.cancel() }
        // Una respuesta tardía no revive el flujo cancelado.
        callback.captured.onFailure(call, IOException("cancelled"))
        assertTrue(pending.isCancelled)
    }

    @Test fun `respuesta HKA se convierte en datos para precarga sin red externa`() = runTest {
        val client = prepare()
        val pending = async { client.consultaAdquiriente("13", "12345678") }
        runCurrent()
        assertEquals("https://hka.example.test/api/adquiriente/consulta", request.captured.url.toString())
        val json = """{"codigo":200,"resultado":"Procesado","nombreReceptor":"Persona Prueba","correoReceptor":"persona@example.test"}"""
        callback.captured.onResponse(call, Response.Builder().request(request.captured)
            .protocol(Protocol.HTTP_1_1).code(200).message("OK").body(json.toResponseBody()).build())
        val result = pending.await()
        assertTrue(result is HkaResponse.Success)
        assertEquals("Persona Prueba", (result as HkaResponse.Success).nombreReceptor)
    }

    @Test fun `fallo y timeout son resultados de captura manual`() = runTest {
        for (failure in listOf(IOException("untrusted detail"), InterruptedIOException("timeout"))) {
            val client = prepare()
            val pending = async { client.consultaAdquiriente("13", "12345678") }
            runCurrent()
            callback.captured.onFailure(call, failure)
            val result = pending.await()
            assertTrue(result is HkaResponse.NetworkError || result is HkaResponse.Timeout)
            val merged = CustomerMerger.merge(
                com.permoda.facturacion.core.model.CustomerData(customerName = ""), null,
                HkaConfig.FallbackMode.HIBRIDO, result
            )
            assertFalse(merged.dianStatus.isValidated)
            assertNotNull(merged.warning)
        }
    }

    @Test fun `URL insegura configuracion invalida y deshabilitado no hacen llamadas`() = runTest {
        for (url in listOf("http://hka.example.test", "https://", "https://user:secret@hka.example.test", "https://hka.example.test?key=x")) {
            val result = HkaClient(config.copy(baseUrl = url), factory).consultaAdquiriente("13", "12345678")
            assertTrue(result is HkaResponse.BadRequest)
        }
        HkaClient(config.copy(enabled = false), factory).consultaAdquiriente("13", "12345678")
        HkaClient(config, factory).consultaAdquiriente("999", "12345678")
        verify { factory wasNot Called }
    }

    @Test fun `respuesta demasiado grande o JSON roto cae a captura manual`() = runTest {
        for (body in listOf("x".repeat(256 * 1024 + 1), "invalid-json")) {
            val client = prepare()
            val pending = async { client.consultaAdquiriente("13", "12345678") }
            runCurrent()
            callback.captured.onResponse(call, Response.Builder().request(request.captured)
                .protocol(Protocol.HTTP_1_1).code(200).message("OK").body(body.toResponseBody()).build())
            assertTrue(pending.await() is HkaResponse.ServerError)
        }
    }
}
