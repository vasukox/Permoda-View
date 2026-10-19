# Plan de trabajo PermodaView

Actualizado el 15 de octubre de 2026. Sustituye el plan histórico de agosto, que describía la captura de la lupa y reglas de empleado ya retiradas.

## Fase local autorizada

- Copia recuperable previa del código y configuración fuera de la carpeta de trabajo; sin inicializar Git.
- Perfil ManageCustomer y selección/lista nativas del POS.
- Operación explícita con identidad congelada, sin inferencias por datos personales.
- Alta con HKA y edición sin HKA; preservación de campos e identidad en la respuesta.
- Mapa configurable del catálogo; separación customerTypeId/contactTypeId.
- Empleado preservado, VIP existente conservado y continuación del cajero sin consentimiento simulado.
- Correcciones de lint, llamador confiable, cancelación y logs sensibles.
- Limpieza de duplicados comprobados y scripts temporales respaldados.
- Pruebas, lint, APK dev para ADB y release CloudLicense, firmados con hash; sin publicación ni instalación.
- Regla permanente: Android versionName="1", versionCode=1 e ICG_MODULE_VERSION=1; overrides incompatibles rechazados.

Los resultados efectivos se registran en docs/RESULTADO_LOCAL.md al finalizar la verificación. No se deben declarar comprobaciones remotas a partir de una compilación o un test de XML.

## Fase posterior en terminal

Consultar docs/PLAN_VALIDACION_POS.md. Se da por confirmada la compatibilidad de ManageCustomer indicada por el usuario. Se contrastará la llamada de esa versión con el perfil configurable, el catálogo real y la persistencia.

## Condiciones externas acotadas

El PDF nuevo describe ManageCustomer, CustomerResult y CustomerSearch, pero no especifica el portador literal de entrada ni el transporte externo de la condición alta/edición. El adaptador documenta sus convenciones, permite configurar nombres y cancela datos ambiguos. Los APK guardados son anteriores a la extensión.

La tabla Con__CustomerFiscal_768 ausente aparece en logs históricos del guardado dinámico. No se modifica CloudLicense ni la base del terminal desde este proyecto. Se revisará su estado al validar la versión actual.

No se asignan por defecto los IDs remotos 0/1/2 ni se interpreta contactTypeId como categoría comercial. Los tres IDs reales se configuran antes de usar el módulo con el catálogo de una instalación.
