package com.permoda.facturacion.entry

import android.app.Activity
import android.os.Bundle

/** La lista es nativa; solo crear/editar se delega mediante ManageCustomer. */
class GetBehaviourActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Se captura antes de construir el result porque ahí `intent` queda
        // sombreado por el Intent de respuesta.
        val receivedAction = intent.action
        val result = android.content.Intent().apply {
            action = receivedAction

            // --- Lo que hace funcionar a PermodaView ---
            ApiActions.CUSTOMER_BEHAVIOUR.forEach { (key, value) -> putExtra(key, value) }
            ApiActions.TOTALIZE_BEHAVIOUR.forEach { (key, value) -> putExtra(key, value) }
            putExtra(ApiActions.Behaviour.CAN_AUDIT, true)

            // --- El resto, explícitamente en false ---
            putExtra(ApiActions.Behaviour.CALL_BEFORE_TOTALIZE_PURCHASE, false)
            putExtra(ApiActions.Behaviour.CALL_BEFORE_TOTALIZE_ORDER, false)
            putExtra(ApiActions.Behaviour.CALL_BEFORE_FINALIZE_STOCK_ADJUSTMENT, false)
            putExtra(ApiActions.Behaviour.CALL_BEFORE_FINALIZE_TRANSFER, false)
            putExtra(ApiActions.Behaviour.CALL_BEFORE_SET_ON_HOLD, false)
            putExtra(ApiActions.Behaviour.CALL_ON_TOTALIZATION_CANCELED, false)
            putExtra(ApiActions.Behaviour.CALL_ON_DOCUMENT_CANCELED, false)
            putExtra(ApiActions.Behaviour.CALL_BEFORE_PRINT, false)
            putExtra(ApiActions.Behaviour.FIX_PRICELIST, false)
            putExtra(ApiActions.Behaviour.CAN_SHOW_AS_SALE_HEADER_OPTION, false)
            putExtra(ApiActions.Behaviour.CALL_ON_TOTALIZE_SALE_AFTER_MIX_MATCH, false)
            putExtra(ApiActions.Behaviour.CALL_AFTER_CREATE, false)
            putExtra(ApiActions.Behaviour.CALL_VOID_DOCUMENT, false)
            putExtra(ApiActions.Behaviour.CALL_ON_SUBTOTAL, false)
            putExtra(ApiActions.Behaviour.REMOVE_CUSTOMER, false)

            // Int, no boolean. 3 = insertar como comentario lo que no exista;
            // es el valor de la referencia de ICG y el menos destructivo.
            // PermodaView no añade líneas, así que en la práctica no aplica.
            putExtra(ApiActions.Behaviour.IF_PRODUCT_NOT_EXISTS, 3)
        }
        android.util.Log.i(
            "PvHandshake",
            "GET_BEHAVIOR ← caller=${callingPackage ?: "desconocido"} " +
                "action=$receivedAction esperada=${ApiActions.GET_BEHAVIOR} " +
                "→ CallOnCustomerSelection=false ManageCustomer=true CreateUpdateCustomer=false " +
                "CallBeforeTotalizeSale=true CanContinue=false canAudit=true (resto false)"
        )
        setResult(RESULT_OK, result)
        finish()
    }
}
