# Instalación posterior por ADB

Referencia escrita; estos comandos no se ejecutaron en esta tarea. Sustituir SERIAL por el identificador del terminal autorizado. No se da por instalada ninguna de las compilaciones locales anteriores.

## Artefactos de versión fija 1

| Uso | APK | Depurable | Firma |
|---|---|---|---|
| Desarrollo / QA por ADB | `C:\Proyectos\PermodaView\app\build\outputs\dev\custvalidation-dev.apk` | Sí | Certificado habitual de PermodaView |
| Release / CloudLicense | `C:\Proyectos\PermodaView\app\build\outputs\cloudlicense\custvalidation.apk` | No | Certificado habitual de PermodaView |

Ambos tienen versionName `1`, versionCode `1`, ICG_MODULE_VERSION `1`, paquete `com.permoda.facturacion` y namespace ICG `custvalidation`. Cada uno incluye su archivo `.apk.sha256`. Ver hashes y comprobación de certificados en `ENTREGA_VERSION_1.md`.

El APK dev reemplaza la misma aplicación; no lleva applicationIdSuffix. Conserva el driver de QA y el comportamiento permisivo del llamador propio de debug. La release aplica la política de llamadores del POS y no habilita el driver. Para uso de tienda se entrega release.

## Comprobar la instalación cuando se autorice conectar el terminal

```powershell
adb -s SERIAL shell dumpsys package com.permoda.facturacion | Select-String 'versionCode=|versionName=|flags=|pkgFlags='
```

Identificar versionCode y si los flags contienen DEBUGGABLE. También se debe contrastar el certificado del APK instalado con el de esta entrega; no basta con que tenga el mismo nombre o paquete. Los APK nuevos coinciden con el certificado del APK histórico release conservado localmente, pero no se leyó la instalación de un terminal.

## Instalación nueva o reemplazo con versionCode 1

Para preparar una terminal de desarrollo:

```powershell
adb -s SERIAL install -r "C:\Proyectos\PermodaView\app\build\outputs\dev\custvalidation-dev.apk"
```

Para release, incluida la vuelta desde nuestro dev con código 1:

```powershell
adb -s SERIAL install -r "C:\Proyectos\PermodaView\app\build\outputs\cloudlicense\custvalidation.apk"
```

`-r` solicita reemplazar conservando los datos. Las condiciones son certificado compatible, código instalado no superior a 1 y permisos de instalación del terminal. Ambas variantes verificadas tienen `testOnly` ausente/falso, por lo que no requieren `-t`. No se ejecutan desinstalación ni borrado de datos.

## Si ya estuviera instalado un versionCode 2

Bajar de 2 a 1 es un downgrade; `-r` por sí solo no lo permite. No se supone que la release de código 2 generada anteriormente esté instalada.

Solo si la versión **ya instalada** es depurable o el firmware autoriza explícitamente ese downgrade, el comando de desarrollo sería:

```powershell
adb -s SERIAL install -r -d "C:\Proyectos\PermodaView\app\build\outputs\dev\custvalidation-dev.apk"
```

Para una release de destino, bajo esas mismas condiciones verificadas:

```powershell
adb -s SERIAL install -r -d "C:\Proyectos\PermodaView\app\build\outputs\cloudlicense\custvalidation.apk"
```

No se debe presentar ninguno como una solución universal. AOSP comprueba el estado depurable de la versión instalada o los permisos especiales de la plataforma; **hacer depurable el APK nuevo no habilita por sí solo el downgrade de una release instalada**. En un terminal de producción con release 2 no depurable, Android normalmente rechazará ambos con INSTALL_FAILED_VERSION_DOWNGRADE. Es necesario acordar el mecanismo de downgrade autorizado por el fabricante/administrador antes de reemplazarla manteniendo los datos. Esta tarea no cambió la regla de versión fija ni preparó un APK con código superior para eludirla. [Implementación AOSP de la comprobación](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android10-release/services/core/java/com/android/server/pm/PackageManagerServiceUtils.java), [manual oficial de ADB](https://android.googlesource.com/platform/packages/modules/adb/+/HEAD/docs/user/adb.1.md).

Si el certificado instalado fuera diferente, `-r` y `-d` no resuelven el conflicto de identidad. El debug estándar anterior usaba otra clave; esta entrega dev utiliza la clave habitual de la aplicación. Se debe revisar la instalación concreta y su mecanismo de migración sin proponer borrar datos como rutina.

## Después de instalar

Comprobar nuevamente versión y flags, refrescar el handshake GET_BEHAVIOR/INITIALIZE del POS y seguir `PLAN_VALIDACION_POS.md`. La instalación correcta no confirma aún el perfil ManageCustomer, las categorías remotas ni la persistencia de una ficha.
