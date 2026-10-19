package com.permoda.facturacion.core.hka

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/** HKA asíncrono: límite total, cuerpo acotado, cancelación real y ningún log de datos. */
class HkaClient(private val config: HkaConfig, private val calls: Call.Factory = OkHttpClient.Builder()
    .connectTimeout(config.timeoutMs.toLong(), TimeUnit.MILLISECONDS)
    .readTimeout(config.timeoutMs.toLong(), TimeUnit.MILLISECONDS)
    .writeTimeout(config.timeoutMs.toLong(), TimeUnit.MILLISECONDS)
    .callTimeout(config.timeoutMs.toLong(), TimeUnit.MILLISECONDS)
    .retryOnConnectionFailure(false).build()) {

    suspend fun consultaAdquiriente(tipoIdentificacion: String, numeroIdentificacion: String): HkaResponse {
        if (!config.enabled) return HkaResponse.ServerError("Integración HKA deshabilitada", 503)
        if (!config.isActive) return HkaResponse.BadRequest("URL HKA inválida; se requiere HTTPS")
        if (!TipoDocumentoMapper.isValidDianCode(tipoIdentificacion) || numeroIdentificacion.isBlank()) {
            return HkaResponse.BadRequest("Tipo y número de documento requeridos")
        }
        val request = Request.Builder().url(config.baseUrl.trimEnd('/') + HkaConfig.ENDPOINT_CONSULTA)
            .header("Accept", "application/json")
            .post(JSONObject().put("tipoIdentificacion", tipoIdentificacion)
                .put("numeroIdentificacion", numeroIdentificacion).toString()
                .toRequestBody("application/json; charset=utf-8".toMediaType())).build()
        return suspendCancellableCoroutine { continuation ->
            val call = calls.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resume(
                        if (e is InterruptedIOException) HkaResponse.Timeout()
                        else HkaResponse.NetworkError("No se pudo consultar HKA")
                    )
                }
                override fun onResponse(call: Call, response: Response) {
                    val result = response.use {
                        try {
                            val source = it.body?.source()
                            source?.request(MAX_RESPONSE_BYTES + 1)
                            if (source == null || source.buffer.size == 0L) {
                                HkaResponse.ServerError("Respuesta HKA vacía", it.code)
                            } else if (source.buffer.size > MAX_RESPONSE_BYTES) {
                                HkaResponse.ServerError("Respuesta HKA demasiado grande", it.code)
                            } else HkaResponse.fromJson(it.code, source.readUtf8())
                        } catch (e: InterruptedIOException) {
                            HkaResponse.Timeout()
                        } catch (e: Exception) {
                            HkaResponse.ServerError("Respuesta HKA no válida", it.code)
                        }
                    }
                    if (continuation.isActive) continuation.resume(result)
                }
            })
        }
    }

    private companion object { const val MAX_RESPONSE_BYTES = 256L * 1024 }
}
