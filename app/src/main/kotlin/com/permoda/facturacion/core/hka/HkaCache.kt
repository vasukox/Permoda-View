package com.permoda.facturacion.core.hka

/**
 * Cache en memoria de respuestas de The Factory HKA.
 *
 * - TTL configurable (en minutos). 0 = sin cache.
 * - Thread-safe (synchronized).
 * - Key = númeroIdentificacion (case-insensitive, sin espacios).
 *
 * La cache vive dentro del proceso. Cuando llega FINALIZE se limpia
 * junto con el resto de la configuración.
 */
class HkaCache(private val ttlMinutes: Int) {

    private data class Entry(
        val response: HkaResponse.Success,
        val storedAtMs: Long
    )

    private val store = mutableMapOf<String, Entry>()

    fun get(numeroIdentificacion: String): HkaResponse.Success? {
        if (ttlMinutes <= 0) return null
        val key = normalize(numeroIdentificacion) ?: return null
        synchronized(store) {
            val entry = store[key] ?: return null
            val ageMs = System.currentTimeMillis() - entry.storedAtMs
            val ttlMs = ttlMinutes * 60_000L
            return if (ageMs <= ttlMs) entry.response else {
                store.remove(key)
                null
            }
        }
    }

    fun put(numeroIdentificacion: String, response: HkaResponse.Success) {
        if (ttlMinutes <= 0) return
        val key = normalize(numeroIdentificacion) ?: return
        synchronized(store) {
            store[key] = Entry(response, System.currentTimeMillis())
        }
    }

    fun clear() {
        synchronized(store) { store.clear() }
    }

    fun size(): Int = synchronized(store) { store.size }

    private fun normalize(numeroIdentificacion: String): String? {
        val cleaned = numeroIdentificacion.trim().replace(Regex("\\s+"), "").lowercase()
        return cleaned.takeIf { it.isNotBlank() }
    }
}
