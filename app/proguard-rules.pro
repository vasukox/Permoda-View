# PermodaView - Reglas ProGuard / R8
# En release: isMinifyEnabled = true

# Mantener clases del modelo (usadas por reflexión / serialización XML)
-keep class com.permoda.facturacion.core.model.** { *; }

# ⚠️ CRÍTICO: los nombres de nuestros enums viajan por el contrato con ICG.
# `CustomerScenario.name` se escribe en el XML como `permodaScenario`, y
# `MergeSource.name` va en el extra `Source` de la auditoría. Si R8 los
# renombra u "desempaqueta" a enteros, HioPosCloud recibiría valores
# ininteligibles — y sólo se vería en release, nunca en debug.
-keep enum com.permoda.facturacion.** { *; }
-keepclassmembers enum com.permoda.facturacion.** {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# Mantener Activities (referenciadas en el AndroidManifest)
-keep public class com.permoda.facturacion.entry.** extends android.app.Activity

# Mantener intent-actions constantes
-keepclassmembers class com.permoda.facturacion.entry.ApiActions {
    public static final java.lang.String *;
}

# Eliminar SÓLO el log de depuración en release.
#
# `Log.i` se conserva a propósito: es el nivel que usamos para los hitos del
# contrato con HioPosCloud (qué respondió la DIAN, qué escenario se resolvió,
# qué se devolvió al POS). Sin eso, un fallo en la terminal deja un logcat con
# el error pero sin nada del camino que llevó a él.
-assumenosideeffects class android.util.Log {
    public static *** v(...);
    public static *** d(...);
}
