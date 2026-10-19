package com.permoda.facturacion.core.manage

import com.permoda.facturacion.core.model.CustomerData

/** Plan inicial compartido por la UI y las pruebas de enrutamiento. */
enum class ManagementStart {
    DOCUMENT_CAPTURE, CREATE_LOOKUP, EDIT_FORM;

    companion object {
        fun resolve(operation: CustomerOperation?, customer: CustomerData): ManagementStart = when {
            operation == CustomerOperation.EDIT -> EDIT_FORM
            operation == CustomerOperation.CREATE && !customer.fiscalId.isNullOrBlank() &&
                !customer.fiscalDocumentType.isNullOrBlank() -> CREATE_LOOKUP
            else -> DOCUMENT_CAPTURE
        }

        /**
         * Alta sin documento = el botón "Nuevo cliente" del POS, no el "Nuevo" del aviso
         * de NIF no encontrado (ese trae el fiscalId tecleado). El módulo no puede
         * consultar el maestro, así que antes de capturar se le recuerda al cajero
         * buscar primero: HioPos rechazaría un duplicado al final del flujo.
         */
        fun needsSearchReminder(operation: CustomerOperation?, customer: CustomerData): Boolean =
            operation == CustomerOperation.CREATE && customer.fiscalId.isNullOrBlank()
    }
}
