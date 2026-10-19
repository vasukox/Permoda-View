# PermodaView

Módulo Android/Kotlin de Permoda para crear y editar clientes de **HioPosCloud (ICG)**, con consulta a **The Factory HKA** y pantalla de **Koaj Lovers**. La consulta, la lupa y la lista de clientes pertenecen al POS nativo.

## Flujo de clientes

- `GET_BEHAVIOR` devuelve `ManageCustomer=true`, `CallOnCustomerSelection=false` y `CreateUpdateCustomer=false`. Esta última bandera pertenece a la ruta histórica ModifyDocument.
- `icg.actions.document.custvalidation.MANAGE_CUSTOMER` abre `ManageCustomerActivity`.
- Alta: identidad explícita no positiva; consulta HKA con tipo y número, precarga nombre/correo y permite captura manual si falla. Si el POS ya trae el documento, no vuelve a pedirlo ni repite la pregunta de crear.
- Edición: identidad positiva congelada; muestra los datos existentes y permite corregirlos **sin consultar HKA**, incluso al cambiar el documento.
- Confirmar crear tras una identificación inexistente es un alta con documento precargado.
- Respuesta: `CustomerResult` y `CustomerSearch` anidado, con `SearchField=FiscalId` y el documento final como `SearchValue`. El POS guarda y controla duplicados. Editar conserva el ID original.
- SELECT_CUSTOMER y MODIFY_DOCUMENT siguen resolviendo a un manejador sin formulario que cancela. No sirven como fallback al buscador; el POS debe refrescar GET_BEHAVIOR después de instalar el APK.
- El consumidor final se selecciona en el POS nativo; no se sustituye una ficha en edición por el genérico.

## Contrato de entrada y límite de evidencia

La documentación local 0.6.4 describe la acción y sus campos, pero omite el nombre literal del extra de entrada y cómo expone el contexto externo de alta/edición. El APK de referencia guardado es 14.20.0.0, anterior a ManageCustomer. No se afirma validación remota de esos detalles.

El adaptador tiene un perfil explícito ajustable por CloudLicense. La convención inicial es XML en `CustomerResult` o URI en `CustomerResultUri`; no se prueban aliases por heurística. Se aceptan raíces `CustomerResult` o `Customer` con campos directos `CustomerField Key="..."`. Se exige `customerId` numérico dentro del XML o el extra nativo `customerId`; si ambos existen deben coincidir. Faltar identidad, una categoría desconocida o cargas ambiguas cancela con un error y no consulta HKA. **Nombre, correo, documento, CustomerSearch y CustomerMode no deciden la operación.**

Las URI deben ser `content://<paquete-llamador>.provider/...`, con permiso concedido por el POS; no se abren rutas arbitrarias. Se limita la entrada a 256 KiB y se rechazan DTD, entidades, campos duplicados y estructuras inesperadas.

Ver [contrato y configuración](docs/INTEGRACION_MANAGE_CUSTOMER.md) y [validación posterior del POS](docs/PLAN_VALIDACION_POS.md).

## Categorías y consentimiento

Los valores 0/1/2 del enum son **identificadores internos de UI**, no un catálogo remoto. Se requieren tres IDs reales, distintos y no negativos, configurados por parámetros. No hay defaults remotos: el código anterior asumía 0/1/2 y el APK de referencia declara STANDAR=1, por lo que no es seguro reutilizarlos sin mapa.

| Categoría entrante | Decisión del cliente | Categoría final |
|---|---|---|
| Normal | Sí | VIP |
| Normal | No o continuar sin inscripción | Normal |
| VIP | No o sin respuesta | VIP; no se revoca accidentalmente |
| Empleado | Cualquier respuesta | Empleado; no se ofrece cambiarlo a normal |

`customerTypeId` es la categoría comercial. **No es `contactTypeId`**, que expresa persona natural/jurídica (1/2 en el APK de referencia). El adaptador preserva este último y los demás campos no editados. `allowPromos` tampoco se convierte automáticamente en aceptación de membresía.

Se conserva la pantalla Koaj Lovers. El cajero puede **continuar sin inscripción**; su botón no simula aceptación del cliente ni emite una auditoría de aceptación. Los eventos del cliente se dirigen al paquete del POS con documento enmascarado, sin nombre ni correo.

## HKA y validación

El endpoint es `/api/adquiriente/consulta`. Se exige HTTPS sin credenciales o query en la URL base. Hay un límite total de 15 s por defecto, respuesta máxima de 256 KiB y cancelación efectiva de la llamada HTTP. Deshabilitado, 404, timeout, error de red o JSON inválido permiten completar el formulario manualmente. No se llama durante edición.

Nombre y apellido, tipo/número de documento, correo y teléfono son obligatorios. El teléfono exige 10 a 15 dígitos locales; se descuentan indicativos soportados. El formulario conserva los dominios HioPos y DIAN separados para evitar confundir, por ejemplo, HioPos 12 (NIT) con DIAN 12 (tarjeta de identidad).

## Parámetros CloudLicense

| Parámetro | Regla o valor inicial |
|---|---|
| CUSTOMER_TYPE_NORMAL_ID | Obligatorio; ID real del catálogo |
| CUSTOMER_TYPE_VIP_ID | Obligatorio; distinto de los demás |
| CUSTOMER_TYPE_EMPLOYEE_ID | Obligatorio; distinto de los demás |
| MANAGE_CUSTOMER_INPUT_EXTRA | CustomerResult; convención ajustable |
| MANAGE_CUSTOMER_INPUT_URI_EXTRA | CustomerResultUri; convención ajustable |
| MANAGE_CUSTOMER_ID_EXTRA | customerId; entero o cadena numérica |
| MANAGE_CUSTOMER_DOCUMENT_CODES | HIOPOS por defecto; admite DIAN explícito |
| HKA_BASE_URL | https://emision21-api.thefactoryhka.com.co |
| HKA_ENABLED | true |
| HKA_TIMEOUT_MS | 15000; rango 1000-30000 |
| HKA_FALLBACK_MODE | HIBRIDO; admite PERMISIVO y CONSERVADOR; no bloquea por fallo de HKA |
| HKA_AUDIT_ENABLED | true |
| KOAJ_ENABLED | true |
| KOAJ_ALLOW_DECLINE | true |
| KOAJ_TIMEOUT_MS | 0: sin autoavance; positivos acotados a 3000-60000 |
| KOAJ_AUDIT_ENABLED | true |

Los ejemplos de `qa/fixtures` usan IDs **ficticios** y HKA deshabilitado. No son configuración de producción. La caché HKA histórica solo se conserva para el driver; la nueva ruta no utiliza una caché por número que pueda mezclar tipos documentales.

## Arquitectura

- `entry/ManageCustomerActivity.kt`: validación del llamador, lectura acotada, control de un único flujo y resultado.
- `core/manage/ManageCustomerContract.kt`: perfil, mapa de categorías, identidad, XML y CustomerSearch.
- `core/manage/ManagementStart.kt`: enrutamiento inicial a edición, consulta o captura.
- `presentation/display/ConsentPresentation.kt`: formulario y espejo existentes; control de cancelación y resultados tardíos.
- `core/customer/CustomerTypeResolver.kt`: regla de membresía independiente del catálogo remoto.
- `core/hka/`: cliente HKA, merge y auditoría saneada.
- `entry/CustomerSelectionActivity.kt`: compatibilidad sin UI para acciones antiguas.
- `core/modify/ModifyDocumentXml.kt`: codec histórico conservado para regresiones, fuera de la nueva ruta y sin escritura de categorías por el campo fiscal antiguo.

## Compilación y entrega local

Stack: Kotlin 2.0.21, AGP 8.10.1, Gradle 9.4.1; Java 17 para el bytecode Android; minSdk 26, compile/target 34. El daemon Gradle usa la herramienta declarada en `gradle/gradle-daemon-jvm.properties`.

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug --offline
.\gradlew.bat :app:apkDevParaAdb :app:apkParaCloudLicense --offline
```

Las tareas de entrega generan **localmente**:

- Desarrollo para ADB: `app/build/outputs/dev/custvalidation-dev.apk`, `debuggable=true`.
- Release para CloudLicense: `app/build/outputs/cloudlicense/custvalidation.apk`, `debuggable=false`.
- Cada APK tiene un archivo lateral `.apk.sha256`.

Ambos usan el paquete `com.permoda.facturacion`, sin sufijos, y el certificado habitual de la aplicación. Comparten instalación y datos; no son dos apps paralelas. La variante debug conserva acceso al driver local. No se sube ni instala nada al compilar.

Se exige la configuración de firma de la aplicación para las dos tareas de entrega. Se usa firma v1/v2 y credenciales de `~/.gradle/gradle.properties`: PERMODA_STORE_FILE, PERMODA_STORE_PASSWORD, PERMODA_KEY_ALIAS y PERMODA_KEY_PASSWORD. Las contraseñas se pasan al firmador mediante su entorno, no en argumentos. La configuration cache está deshabilitada para no serializar la configuración de firma. `assembleDebug` usa también esa firma cuando está configurada; sin ella puede usar el certificado debug estándar, pero `apkDevParaAdb` no entrega un APK sin la firma de la aplicación.

**Regla permanente: versionName="1", versionCode=1 e ICG_MODULE_VERSION=1 en ambas variantes.** No hay incrementos automáticos, sufijos de versión ni cálculo por fecha o compilación. Los overrides antiguos `PERMODA_VERSION_CODE`, `PERMODA_VERSION_NAME` y `PERMODA_ICG_MODULE_VERSION` solo admiten `1`; cualquier otro valor detiene Gradle. El extra ICG `Version` sigue siendo un entero; la versión visible Android es una cadena. Se conserva namespace/apk_name `custvalidation`.

Ver [entrega vigente](docs/ENTREGA_VERSION_1.md) e [instalación posterior por ADB](docs/INSTALACION_ADB.md), incluido el caso de una instalación previa con versionCode 2. Mantener la versión fija permite reemplazos con el mismo código por ADB; un sistema de distribución que solo detecte versiones superiores podría requerir una actualización manual.

Verificación manual del artefacto local: `apksigner verify --min-sdk-version 23 --verbose <APK>` para comprobar ambas firmas, y `Get-FileHash <APK> -Algorithm SHA256` para contrastar el archivo lateral.

## Seguridad, evidencias y límites

Las entradas de escritura aceptan únicamente al llamador obtenido de Android; se retiró el fallback manipulable `callerPackage`. FINALIZE también valida al emisor. Debug conserva acceso para el driver local; no es la variante a entregar a tiendas.

`qa/logs` contiene evidencias históricas sensibles; no se han publicado ni tratado como archivos desechables. Está excluido de futuras incorporaciones a Git junto con APK, extracciones y configuración local. No hay repositorio Git ni GitHub Actions configurados en esta carpeta. La copia previa a esta implementación y el inventario de limpieza quedan fuera del proyecto en el directorio de artefactos de la conversación.

Las pruebas locales validan dominio, XML, errores, cancelación HTTP, perfil y manifiesto. No certifican una venta ni persistencia en Cloud. La fase de terminal comprobará los literales de entrada, la identidad recibida, el catálogo remoto y el guardado/recarga. La ausencia de terminal no bloquea el trabajo local.
