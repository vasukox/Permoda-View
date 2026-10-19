package com.permoda.facturacion.core.hka

/**
 * Resultado tipado de una consulta a The Factory HKA (DIAN).
 *
 * Sealed para que el caller maneje todos los casos con `when` exhaustivo
 * y no olvide ningún branch.
 */
sealed class HkaResponse {

    /** Código HTTP (o 0 si no fue un HTTP error, ej. NetworkError). */
    abstract val httpCode: Int

    /**
     * 200 OK · `resultado == "Procesado."`
     * Datos fiscales oficiales de la DIAN.
     */
    data class Success(
        val nombreReceptor: String,
        val correoReceptor: String,
        override val httpCode: Int = 200,
        val codigo: Int = 200,
        val mensaje: String = "Exitoso.",
        /** Default alineado con la respuesta real de The Factory HKA (sin punto final). */
        val resultado: String = "Procesado"
    ) : HkaResponse()

    /**
     * 404 o cuerpo con `codigo == 404`.
     * La DIAN no tiene al cliente registrado — se usan los datos locales.
     */
    data class NotFound(
        val mensaje: String,
        override val httpCode: Int = 404
    ) : HkaResponse()

    /**
     * 408 · Timeout agotado. Llamador decide si reintentar.
     */
    data class Timeout(
        val mensaje: String = "Timeout · La DIAN no respondió",
        override val httpCode: Int = 408
    ) : HkaResponse()

    /**
     * 400 · Petición malformada (tipoIdentificacion faltante, etc.).
     * No retry — el caller debe revisar el input.
     */
    data class BadRequest(
        val mensaje: String,
        override val httpCode: Int = 400,
        val codigo: Int = 400
    ) : HkaResponse()

    /**
     * 429 · Too Many Requests — caller debe esperar y reintentar.
     */
    data class RateLimited(
        val mensaje: String = "Demasiadas solicitudes",
        override val httpCode: Int = 429
    ) : HkaResponse()

    /**
     * 5xx (500/502/503/504) o error interno de HKA.
     * Aplica el modo de fallback configurado.
     */
    data class ServerError(
        val mensaje: String,
        override val httpCode: Int
    ) : HkaResponse()

    /**
     * Sin red, DNS falló, etc. No es un HTTP status.
     */
    data class NetworkError(
        val mensaje: String,
        val cause: String? = null
    ) : HkaResponse() {
        override val httpCode: Int = 0
    }

    val isExitoso: Boolean
        get() = this is Success &&
            codigo == 200 &&
            resultado.trim().trimEnd('.').equals("Procesado", ignoreCase = true)

    val isRetryable: Boolean
        get() = this is Timeout || this is ServerError || this is RateLimited || this is NetworkError

    companion object {

        /**
         * Correos de relleno que devuelve la DIAN cuando el contribuyente no
         * tiene uno registrado.
         *
         * ⚠️ Verificado en producción: para la cédula 95656565 la DIAN devolvió
         * `correoReceptor: "sincorreo@sincorreo.com"`. Si se toma como válido,
         * pasa la validación de campos obligatorios y **la factura electrónica
         * se envía a un buzón que no existe** — justo lo que el correo
         * obligatorio pretendía evitar.
         *
         * Se comparan en minúsculas y sin espacios.
         */
        private val PLACEHOLDER_EMAILS = setOf(
            "sincorreo@sincorreo.com",
            "nocorreo@nocorreo.com",
            "sinemail@sinemail.com",
            "no@no.com",
            "na@na.com",
            "sincorreo@gmail.com"
        )

        /** True si el correo es un relleno y hay que tratarlo como ausente. */
        fun isPlaceholderEmail(email: String?): Boolean {
            val clean = email?.trim()?.lowercase().orEmpty()
            return clean.isNotBlank() && clean in PLACEHOLDER_EMAILS
        }

        /** Mapea el body JSON de HKA a un [HkaResponse]. */
        fun fromJson(httpCode: Int, body: String): HkaResponse {
            val json = org.json.JSONObject(body)
            val codigo = json.optInt("codigo", httpCode)
            val mensaje = json.optString("mensaje", "")
            val resultado = json.optString("resultado", "")
            val nombre = json.optString("nombreReceptor", "")
            val correoCrudo = json.optString("correoReceptor", "")

            // Un relleno equivale a "sin correo": así el flujo lo pide igual que
            // si la DIAN no hubiera devuelto nada.
            val correo = if (isPlaceholderEmail(correoCrudo)) "" else correoCrudo

            val procesado = httpCode == 200 && codigo == 200 &&
                resultado.trim().trimEnd('.').equals("Procesado", ignoreCase = true)

            return when {
                procesado && nombre.isNotBlank() ->
                    Success(nombre, correo, httpCode, codigo, mensaje, resultado)
                // 200 "Procesado" pero sin nombre: la DIAN respondió bien y no
                // tiene el dato. Es un "no encontrado", no un error de
                // servidor — antes caía en el `else` y se reportaba como
                // ServerError("Respuesta inesperada (200)").
                procesado ->
                    NotFound(
                        mensaje.ifBlank { "La DIAN no tiene datos para este documento" },
                        httpCode
                    )
                httpCode == 404 || codigo == 404 ->
                    NotFound(mensaje.ifBlank { "Cliente no encontrado en la DIAN" }, httpCode)
                httpCode == 400 || codigo == 400 ->
                    BadRequest(mensaje.ifBlank { "Petición malformada" }, httpCode, codigo)
                httpCode == 408 ->
                    Timeout(mensaje.ifBlank { "Timeout · La DIAN no respondió" }, httpCode)
                httpCode == 429 ->
                    RateLimited(mensaje.ifBlank { "Demasiadas solicitudes" }, httpCode)
                // HKA devuelve HTTP 200 con `codigo` de error en el cuerpo.
                // Verificado en producción con un documento inexistente:
                //   200 {"codigo":500,"mensaje":"No fue posible obtener los
                //        datos desde la DIAN. Verifique que el identificador
                //        sea correcto o intente de nuevo más tarde."}
                // Antes esto caía en el `else` final por accidente; se hace
                // explícito para que el día que cambie el formato se note.
                httpCode in 500..599 || codigo in 500..599 ->
                    ServerError(mensaje.ifBlank { "Error del servidor HKA ($codigo)" }, httpCode)
                else -> ServerError(mensaje.ifBlank { "Respuesta inesperada ($httpCode)" }, httpCode)
            }
        }
    }
}
