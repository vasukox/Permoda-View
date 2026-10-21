package com.permoda.facturacion.core.manage

import java.io.InputStream

/** Lectura acotada y autorización de URI, independiente de las clases Android. */
object ManageCustomerTransport {
    data class Resource(val scheme: String?, val authority: String?)

    /**
     * @param maxBytes el documento de venta de TOTALIZE_SALE trae líneas y es
     *   más grande que un cliente, por eso el tope es configurable.
     */
    fun read(
        inline: String?,
        resource: Resource?,
        systemCaller: String?,
        maxBytes: Int = ManagedCustomerSession.MAX_BYTES,
        open: () -> InputStream?
    ): String {
        require(inline == null || resource == null) { "Se recibió más de una carga de cliente" }
        if (inline != null) {
            require(inline.toByteArray(Charsets.UTF_8).size <= maxBytes) { "XML de cliente demasiado grande" }
            return inline
        }
        require(resource != null) { "Falta el XML de cliente en los extras configurados" }
        require(systemCaller != null && resource.scheme == "content" && resource.authority == "$systemCaller.provider") {
            "URI de cliente no autorizada"
        }
        val data = open()?.use { stream ->
            val buffer = ByteArray(maxBytes + 1)
            var total = 0
            while (total < buffer.size) {
                val count = stream.read(buffer, total, buffer.size - total)
                if (count < 0) break
                if (count == 0) {
                    val single = stream.read()
                    if (single < 0) break
                    buffer[total++] = single.toByte()
                } else total += count
            }
            require(total <= maxBytes) { "XML de cliente demasiado grande" }
            buffer.copyOf(total)
        } ?: throw IllegalArgumentException("No se pudo leer el XML de cliente")
        return data.toString(Charsets.UTF_8)
    }
}
