package com.permoda.facturacion.core.hka

import com.permoda.facturacion.core.model.CustomerData

/**
 * Lógica de merge entre los datos locales (HioPosCloud) y la respuesta
 * oficial de la DIAN (The Factory HKA).
 *
 * Reglas de prioridad:
 *  - `customerName` y `email`  → gana DIAN si trae valor no vacío
 *  - resto de campos           → gana HioPosCloud (datos operativos)
 *  - `fiscalDocumentType` y `fiscalId` → se mantienen los locales
 *    (deberían coincidir; si no, el operador deberá corregir)
 */
object CustomerMerger {

    /**
     * Fusiona los datos. `hka` puede ser `null` (DIAN no respondió o no encontró).
     * Devuelve un [MergeResult] con el cliente final + metadatos para auditoría.
     */
    fun merge(
        local: CustomerData,
        hka: HkaResponse.Success?,
        fallbackMode: HkaConfig.FallbackMode,
        hkaError: HkaResponse? = null
    ): MergeResult {
        val nombreFinal = when {
            hka != null && hka.nombreReceptor.isNotBlank() -> hka.nombreReceptor
            else -> local.customerName
        }
        val emailFinal = when {
            hka != null && hka.correoReceptor.isNotBlank() -> hka.correoReceptor
            else -> local.email
        }

        val merged = local.copy(
            customerName = nombreFinal,
            email = emailFinal
        )

        val source = when {
            hka != null && hka.isExitoso && merged != local -> MergeSource.DIAN_OVERRIDE
            hka != null && hka.isExitoso                     -> MergeSource.DIAN_CONFIRMED
            hka == null && hkaError == null                 -> MergeSource.LOCAL_ONLY
            hkaError is HkaResponse.NotFound                -> MergeSource.DIAN_NOT_REGISTERED
            // Regla de oro: un fallo de la DIAN NUNCA bloquea la venta.
            // `CONSERVADOR` solía devolver BLOCKED aquí y la pantalla mostraba
            // un error sin salida; ahora sólo cambia si se avisa o no.
            else -> when (fallbackMode) {
                HkaConfig.FallbackMode.PERMISIVO -> MergeSource.LOCAL_FALLBACK
                HkaConfig.FallbackMode.HIBRIDO,
                HkaConfig.FallbackMode.CONSERVADOR -> MergeSource.LOCAL_FALLBACK_WARNING
            }
        }

        val warning = when (source) {
            MergeSource.LOCAL_FALLBACK_WARNING ->
                "No se pudo validar con la DIAN. Revisa y completa los datos manualmente."
            MergeSource.DIAN_NOT_REGISTERED ->
                "El documento no está registrado en la DIAN. Completa los datos manualmente."
            else -> null
        }

        return MergeResult(
            customer = merged,
            source = source,
            warning = warning,
            hkaResponse = hka ?: hkaError
        )
    }
}

data class MergeResult(
    val customer: CustomerData,
    val source: MergeSource,
    /** Texto a mostrar al cliente si los datos no fueron verificados. */
    val warning: String?,
    /** Respuesta cruda de HKA (Success o error) para auditoría. */
    val hkaResponse: HkaResponse?
) {
    /** Qué puede afirmar la pantalla del cliente sobre la DIAN. */
    val dianStatus: DianStatus
        get() = DianStatus.from(source)
}

enum class MergeSource {
    /** DIAN devolvió datos y sobrescribió nombre/email. */
    DIAN_OVERRIDE,
    /** DIAN confirmó los datos locales sin cambios. */
    DIAN_CONFIRMED,
    /**
     * DIAN respondió 404: el documento **no está registrado**. No es un error
     * — es el escenario 3, y el cliente debe escribir todos sus datos.
     */
    DIAN_NOT_REGISTERED,
    /** Sin DIAN (deshabilitado o no consultado). */
    LOCAL_ONLY,
    /** DIAN falló y el modo es permisivo: datos locales, sin aviso. */
    LOCAL_FALLBACK,
    /** DIAN falló: datos locales + aviso para completar a mano. */
    LOCAL_FALLBACK_WARNING
    // No hay estado de bloqueo a propósito: ningún fallo de la DIAN puede
    // dejar al vendedor sin poder facturar.
}
