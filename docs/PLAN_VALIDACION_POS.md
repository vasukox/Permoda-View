# Validación posterior en HioPosCloud

Esta fase está planificada después de completar la implementación y las comprobaciones locales. No se necesita un terminal para ejecutar las pruebas JVM, compilar o revisar lint. No se ha instalado ni publicado el APK desde esta tarea.

Se acepta la compatibilidad ManageCustomer confirmada por el usuario. Antes de comprobar ventas se contrastarán los nombres de extras/URI, la identidad y el dominio de códigos con el perfil configurado. Los IDs de categorías se tomarán del catálogo de esa instalación. La captura técnica debe excluir XML con datos personales y credenciales; basta registrar nombres y tipos de extras, operación y resultado anonimizado.

| Caso | Resultado esperado |
|---|---|
| Abrir lupa/lista/buscar y seleccionar cliente | Consulta nativa; ninguna pantalla PermodaView |
| Nuevo/crear cliente | MANAGE_CUSTOMER; alta; HKA al tener documento y tipo |
| Identificación inexistente, responder No | Sigue el flujo nativo; no se crea cliente ni se llama a HKA |
| Identificación inexistente, responder Sí | Alta con documento precargado, sin repetir captura/pregunta |
| Editar existente y completar datos | ID conservado; ninguna consulta HKA |
| Corregir número/tipo, correo y teléfono al editar | Sigue siendo edición; ID original; verificar guardado y recarga |
| Colisión de identificación en alta | POS advierte y no crea duplicado |
| Colisión en edición | Comprobar la política efectiva del POS sin sobrescribir otro cliente |
| Normal acepta Koaj Lovers | Categoría remota VIP al guardar y al volver a abrir |
| Normal responde No | Categoría normal; ficha se guarda |
| VIP responde No | Conserva VIP; no revocación implícita |
| Empleado | Conserva categoría empleado; no elección para degradarlo |
| Cajero continúa sin inscripción | No marca aceptación del cliente; normal permanece normal |
| Persona natural/jurídica | contactTypeId original no cambia al responder a Koaj |
| Permisos y dirección preexistentes | Se preservan; allowPromos no se deriva de membresía |
| HKA 404, sin red, timeout o JSON inválido | Captura manual con aviso; se puede guardar un formulario válido |
| Cancelar durante HKA y empezar otra operación | Sin pantalla huérfana ni datos de la operación anterior |
| Categorías sin configurar, desconocidas o identidad ausente | Error explícito, sin HKA ni aceptación |
| URI autorizada / URI ajena / XML grande | Solo se admite la carga autorizada y acotada |
| Reinicio del POS | GET_BEHAVIOR e INITIALIZE coherentes; no vuelve a capturar la lupa |

Guardar una evidencia anonimizada de resultado y recarga por cada categoría. Una respuesta RESULT_OK solo indica que el módulo entregó el resultado; no certifica que Cloud lo haya persistido. Revisar por separado el estado de los campos dinámicos del módulo 768 si reaparece el error histórico Con__CustomerFiscal_768.
