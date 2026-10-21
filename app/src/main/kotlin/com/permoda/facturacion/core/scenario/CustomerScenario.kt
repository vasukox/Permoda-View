package com.permoda.facturacion.core.scenario

import com.permoda.facturacion.core.hka.DianStatus
import com.permoda.facturacion.core.model.CustomerData
import com.permoda.facturacion.core.validation.CustomerValidator

/**
 * Los escenarios del flujo `SELECT_CUSTOMER`.
 *
 * | # | En HioPosCloud        | DIAN         | Flujo                                    |
 * |---|-----------------------|--------------|------------------------------------------|
 * | 1 | ✅ con datos completos | no se consulta | Mostrar y confirmar                     |
 * | 2 | ❌ no existe           | ✅ encontrado  | Compuerta → documento → datos de DIAN   |
 * | 3 | ✅ con datos incompletos | no se consulta | Compuerta → el cliente completa       |
 * | 4 | ❌ no existe           | ❌ no encontrado | Compuerta → documento → todo a mano   |
 *
 * **La DIAN sólo se consulta cuando el cliente es nuevo para HioPosCloud.**
 * Por eso el escenario 3 no es "existe en Hiopos y no en la DIAN" —eso
 * exigiría consultar en cada venta—, sino "existe en Hiopos pero le faltan
 * datos para facturar". Se detecta con [CustomerValidator], sin red y sin
 * costo en caja.
 *
 * La señal de "existe en HioPosCloud" es **`customerId`**: es lo único que el
 * POS asigna a los clientes de su maestro. Deducirlo del nombre o del
 * documento confundía "el cajero tecleó algo" con "el cliente existe".
 */
enum class CustomerScenario {

    /** 1. Existe en HioPosCloud y sus datos sirven para facturar. */
    EDIT,

    /** 2. Nuevo en HioPosCloud, la DIAN lo encontró. */
    CREATE_FROM_DIAN,

    /** 3. Existe en HioPosCloud pero le faltan datos para facturar. */
    COMPLETE_EXISTING,

    /** 4. Nuevo en HioPosCloud y la DIAN tampoco lo tiene. */
    CREATE_MANUAL,

    /** Transversal: el cliente no quiere entregar sus datos. */
    CONSUMIDOR_FINAL;

    /** True si HioPosCloud tendrá que crear el cliente con lo que devolvamos. */
    val createsCustomer: Boolean
        get() = this == CREATE_FROM_DIAN || this == CREATE_MANUAL

    /**
     * True si el flujo tiene que detenerse a pedirle permiso al vendedor
     * antes de que el cliente haga nada.
     */
    val needsVendorApproval: Boolean
        get() = this == CREATE_FROM_DIAN || this == CREATE_MANUAL || this == COMPLETE_EXISTING

    /**
     * Action del broadcast de auditoría, uno por escenario.
     * Nombres acordados con ICG en la guía de integración.
     */
    val auditAction: String
        get() = when (this) {
            EDIT              -> "cliente_existente"
            CREATE_FROM_DIAN  -> "cliente_creado_con_dian"
            COMPLETE_EXISTING -> "cliente_completado"
            CREATE_MANUAL     -> "cliente_creado_sin_dian"
            CONSUMIDOR_FINAL  -> "cliente_consumidor_final"
        }

    companion object {

        /**
         * Resuelve el escenario **después** de que el cliente teclea su
         * documento, y **sin tocar la red**.
         *
         * PermodaView es el único punto donde se captura el documento, así que
         * el flujo siempre arranca con esa pantalla. Recién con el documento en
         * mano se puede saber por dónde va la venta.
         *
         * ⚠️ **Limitación de la integración:** no podemos consultar el maestro
         * de clientes de HioPosCloud —ICG no expone una búsqueda—. Lo único que
         * tenemos es el cliente que el POS trajo en el `CustomerResult`. Por eso
         * "está en HioPos" se resuelve **comparando el documento tecleado con
         * el que vino en el Intent**.
         *
         * @param incoming el cliente que mandó HioPosCloud (puede venir vacío).
         * @param typedDocId documento que acabó de teclear el cliente.
         */
        fun afterDocument(
            incoming: CustomerData,
            typedDocId: String,
            rules: CustomerValidator.Rules = CustomerValidator.Rules()
        ): CustomerScenario {
            val isTheIncomingCustomer =
                existsInHiopos(incoming) && sameDocument(incoming.fiscalId, typedDocId)

            // No es el cliente que traía el POS → para HioPosCloud es nuevo.
            // El escenario definitivo (2 o 4) lo decide la DIAN después.
            if (!isTheIncomingCustomer) return CREATE_MANUAL

            return if (CustomerValidator.validateForSave(incoming, rules).isValid) EDIT
            else COMPLETE_EXISTING
        }

        /**
         * Compara sólo el **número**, no el tipo.
         *
         * El número es lo que identifica a la persona; el tipo es metadato que
         * se equivoca a menudo (un NIT registrado como cédula, por ejemplo). Si
         * exigiéramos que ambos coincidieran, un tipo mal grabado en HioPos
         * haría que creáramos un cliente duplicado.
         */
        fun sameDocument(a: String?, b: String?): Boolean {
            val left = a?.trim().orEmpty()
            val right = b?.trim().orEmpty()
            return left.isNotBlank() && left == right
        }

        /** True si HioPosCloud ya tiene este cliente en su maestro. */
        fun existsInHiopos(customer: CustomerData): Boolean =
            !customer.customerId.isNullOrBlank()

        /**
         * Paso 3: el cliente NO estaba en HioPosCloud y ya consultamos la DIAN.
         *
         * Cualquier cosa que no sea una validación exitosa cae en
         * [CREATE_MANUAL] — 404, timeout, 5xx, sin red o DIAN deshabilitada.
         * **Nunca se bloquea**: el peor caso es escribir los datos a mano.
         */
        fun afterDian(status: DianStatus): CustomerScenario =
            if (status.isValidated) CREATE_FROM_DIAN else CREATE_MANUAL
    }
}
