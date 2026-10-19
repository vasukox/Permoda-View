package com.permoda.facturacion.core.hka

/**
 * Qué sabemos realmente del cliente frente a la DIAN, después del merge.
 *
 * Existe porque la pantalla del cliente **no debe afirmar** que alguien fue
 * "validado en la DIAN" cuando la DIAN respondió 404 o cuando no se pudo
 * consultar. Antes de esto, [MergeSource] mezclaba "no registrado en DIAN"
 * y "no consultamos la DIAN" en un mismo valor (`LOCAL_ONLY`), así que la
 * UI no podía distinguirlos y mostraba el mismo ✓ verde en los tres casos.
 */
enum class DianStatus {

    /** La DIAN respondió y confirmó los datos fiscales. Escenario 1 y 2. */
    VALIDATED,

    /**
     * La DIAN respondió explícitamente que el documento **no está registrado**
     * (404). Escenario 3: el cliente debe escribir todos sus datos.
     */
    NOT_REGISTERED,

    /**
     * No hay respuesta de la DIAN (deshabilitada, sin red, timeout, 5xx, o
     * faltaba tipo/documento). No sabemos si existe: no afirmamos nada.
     */
    UNVERIFIED;

    val isValidated: Boolean get() = this == VALIDATED

    companion object {
        /** Traduce el resultado del merge al estado que la UI puede afirmar. */
        fun from(source: MergeSource): DianStatus = when (source) {
            MergeSource.DIAN_OVERRIDE,
            MergeSource.DIAN_CONFIRMED        -> VALIDATED
            MergeSource.DIAN_NOT_REGISTERED   -> NOT_REGISTERED
            MergeSource.LOCAL_ONLY,
            MergeSource.LOCAL_FALLBACK,
            MergeSource.LOCAL_FALLBACK_WARNING -> UNVERIFIED
        }
    }
}
