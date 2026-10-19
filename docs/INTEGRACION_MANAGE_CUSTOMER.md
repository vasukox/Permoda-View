# Contrato de integración de clientes

## Evidencia y convenciones

HPCL.025 0.6.4 ultimate, páginas físicas 11 y 17-18: ManageCustomer conserva la lista nativa y delega altas/ediciones. Páginas 41-42: CustomerResult puede contener CustomerSearch. La página 7 exige devolver la misma acción. El índice y la portada del PDF no están completamente actualizados; se citan páginas físicas.

El APK local `icg.android.start` 14.20.0.0 es anterior a esta extensión. Demuestra `customerId=-1` al abrir un alta, un ID existente al editar e IDs provisionales negativos; no demuestra cómo la nueva acción transporta esos datos externamente. CustomerMode de los scripts anteriores no se usa como contrato.

El perfil implementado es una convención explícita y configurable, pendiente de contrastar con la llamada de la versión actual en la fase de terminal:

| Elemento | Perfil inicial | Ajuste CloudLicense |
|---|---|---|
| XML como String extra | CustomerResult | MANAGE_CUSTOMER_INPUT_EXTRA |
| XML por content URI Parcelable | CustomerResultUri | MANAGE_CUSTOMER_INPUT_URI_EXTRA |
| Identidad nativa extra | customerId | MANAGE_CUSTOMER_ID_EXTRA |
| Identidad en XML | CustomerField Key="customerId" | Mantiene el contrato de campos |
| Dominio fiscal numérico | HIOPOS | MANAGE_CUSTOMER_DOCUMENT_CODES=HIOPOS o DIAN |

No se prueban aliases automáticamente. El XML debe tener raíz CustomerResult o Customer y campos CustomerField directos. No se aceptan cargas múltiples, IDs contradictorios, DTD/entidades, campos repetidos, XML de otro contrato o más de 256 KiB. Si falta la identidad se cancela, aunque el documento/nombre vengan completos. La URI requiere esquema content y autoridad del llamador confiable con sufijo `.provider`; no se leen paths arbitrarios.

## Identidad y operación

- ID numérico positivo: edición. El ID original se congela y no se toma del formulario al construir la respuesta.
- ID explícito cero o negativo: creación. Se descarta el ID provisional al devolver el cliente.
- ID ausente o no numérico: error, sin consulta HKA y sin resultado de aceptación.
- Corregir el documento de un cliente existente sigue siendo edición y no autoriza HKA.
- Crear después de una identificación inexistente es una creación con documento precargado.

CustomerSearch no decide esta operación ni consulta el maestro. El POS mantiene su contexto y efectúa el guardado y la comprobación de duplicados. El adaptador devuelve la identidad original de una edición y el documento final como criterio FiscalId. El comportamiento de la versión actual ante una edición que colisione con otro documento deberá comprobarse al validar el POS; la app no tiene una consulta del maestro para resolverlo localmente.

## Categoría comercial

Los parámetros CUSTOMER_TYPE_NORMAL_ID, CUSTOMER_TYPE_VIP_ID y CUSTOMER_TYPE_EMPLOYEE_ID son obligatorios, no negativos y distintos. No existen defaults remotos. Se rechaza una categoría de entrada desconocida; en edición también se rechaza que falte. Un alta sin categoría usa el normal configurado. Los valores internos de UI 0/1/2 nunca se escriben directamente al POS.

El nuevo resultado usa `CustomerField Key="customerTypeId"`. La combinación de ese campo con CustomerResult deriva de las secciones de la nueva API; el ejemplo general del PDF omite varios campos nuevos. Su persistencia remota no se afirma a partir de los tests locales.

contactTypeId se preserva independientemente. En el APK histórico sus valores 1/2 significan persona natural/jurídica. La ruta antigua que leía CustomerTypeId en PascalCase lo asignaba a ese campo fiscal, por lo que se retiró esa escritura del codec histórico. Tampoco se genera aceptación comercial allowPromos a partir de la membresía: el valor recibido se conserva.

La decisión Sí del cliente promueve Normal a VIP; No o continuar sin inscripción deja Normal. Un VIP existente conserva membresía hasta una revocación explícita. Empleado conserva su categoría y no recibe elección para pasar a normal. El cajero no puede forzar una aceptación.

## Configuración

Las claves llegan en Parameters de INITIALIZE. Ejemplo de plantilla para una instalación; reemplazar los tres marcadores con el catálogo real, no usar los códigos de las pruebas:

```xml
<Configuration><Parameters>
  <Param Key="CUSTOMER_TYPE_NORMAL_ID">ID_NORMAL_REAL</Param>
  <Param Key="CUSTOMER_TYPE_VIP_ID">ID_VIP_REAL</Param>
  <Param Key="CUSTOMER_TYPE_EMPLOYEE_ID">ID_EMPLEADO_REAL</Param>
  <Param Key="MANAGE_CUSTOMER_INPUT_EXTRA">CustomerResult</Param>
  <Param Key="MANAGE_CUSTOMER_INPUT_URI_EXTRA">CustomerResultUri</Param>
  <Param Key="MANAGE_CUSTOMER_ID_EXTRA">customerId</Param>
  <Param Key="MANAGE_CUSTOMER_DOCUMENT_CODES">HIOPOS</Param>
</Parameters></Configuration>
```

Los marcadores no numéricos cancelan con un mensaje de configuración. Tras instalar el artefacto en la fase autorizada correspondiente, HioPos debe refrescar GET_BEHAVIOR e INITIALIZE. Esta documentación no ejecuta ni autoriza un despliegue.

## Respuesta

Cadena XML en el extra CustomerResult, con la misma acción MANAGE_CUSTOMER y RESULT_OK. Los datos se validan antes de responder. Las cancelaciones y errores devuelven RESULT_CANCELED y ErrorMessage. Ejemplo ilustrativo con categoría remota ficticia 47:

```xml
<CustomerResult>
  <CustomerField Key="customerId">123</CustomerField>
  <CustomerField Key="customerName">Persona Prueba</CustomerField>
  <CustomerField Key="fiscalId">12345678</CustomerField>
  <CustomerField Key="fiscalDocumentType">9</CustomerField>
  <CustomerField Key="email">persona@example.test</CustomerField>
  <CustomerField Key="phone">3001234567</CustomerField>
  <CustomerField Key="customerTypeId">47</CustomerField>
  <CustomerField Key="contactTypeId">1</CustomerField>
  <CustomerSearch>
    <CustomerSearchField Key="SearchField">FiscalId</CustomerSearchField>
    <CustomerSearchField Key="SearchValue">12345678</CustomerSearchField>
  </CustomerSearch>
</CustomerResult>
```

customerId se omite al crear. Se preservan los campos originales que no edita la UI, incluidos dirección, naturaleza fiscal y permisos. No se insertan etiquetas propias de la ruta histórica ni se fuerzan propiedades de facturación del maestro.

## Seguridad y cancelación

El llamador de escritura proviene de Android; un extra callerPackage no concede confianza. FINALIZE también está protegido. Solo hay una pantalla de gestión activa; un segundo intento no altera su estado. La cancelación cierra ambas pantallas, cancela la llamada HTTP y descarta resultados tardíos mediante generación de consulta. Ni los logs ni la auditoría incluyen XML, nombre, correo o teléfono. Los broadcasts están restringidos al POS.

La integración HKA tiene timeout total, HTTPS y tamaño de respuesta limitado. Las pruebas utilizan una Call.Factory simulada: no hacen llamadas a HKA ni a servicios de producción.
