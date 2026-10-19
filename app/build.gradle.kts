import java.io.File
import java.security.MessageDigest

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

val icgApkName = (project.findProperty("PERMODA_ICG_APK_NAME") as String?)
    ?.takeIf { it.isNotBlank() }
    ?: "custvalidation"
require(icgApkName.matches(Regex("[A-Za-z][A-Za-z0-9_.-]*"))) { "Nombre de módulo ICG inválido" }

// Regla del proyecto: Android y el protocolo ICG permanecen siempre en 1.
// Rechazar overrides antiguos evita compilar accidentalmente otra versión.
listOf("PERMODA_VERSION_CODE", "PERMODA_VERSION_NAME", "PERMODA_ICG_MODULE_VERSION").forEach { key ->
    val requested = project.findProperty(key)?.toString()?.trim()
    require(requested == null || requested == "1") { "$key debe ser 1: la versión de PermodaView es fija" }
}

android {
    namespace = "com.permoda.facturacion"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.permoda.facturacion"
        minSdk = 26
        targetSdk = 34
        
        versionCode = 1
        versionName = "1"
        buildConfigField("int", "ICG_MODULE_VERSION", "1")
        manifestPlaceholders["icgApkName"] = icgApkName
        buildConfigField("String", "ICG_APK_NAME", "\"$icgApkName\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    base.archivesName.set("custvalidation")

    sourceSets {
        getByName("main") {
            java.srcDirs("src/main/kotlin")
        }
        getByName("test") {
            java.srcDirs("src/test/kotlin")
        }
    }

    val permodaStoreFile = (project.findProperty("PERMODA_STORE_FILE") as String?)
        ?.takeIf { file(it).exists() }

    signingConfigs {
        if (permodaStoreFile != null) {
            create("release") {
                storeFile = file(permodaStoreFile)
                storePassword = project.findProperty("PERMODA_STORE_PASSWORD") as String?
                keyAlias = project.findProperty("PERMODA_KEY_ALIAS") as String?
                keyPassword = project.findProperty("PERMODA_KEY_PASSWORD") as String?
                enableV1Signing = true
                enableV2Signing = true
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isDebuggable = true
            // Misma identidad para reemplazar el módulo instalado durante QA.
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    implementation(libs.bundles.androidx.base)
    implementation(libs.bundles.androidx.lifecycle)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)

    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.turbine)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.json)

    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.espresso.core)
}

fun registerApkDelivery(taskName: String, signerTask: String, variant: String,
                        folder: String, outputName: String) {
    tasks.register<Copy>(taskName) {
        group = "permoda"
        description = "Genera el APK $variant firmado y su SHA-256 en outputs/$folder."
        dependsOn(signerTask)
        from(layout.buildDirectory.file("outputs/apk/$variant/custvalidation-$variant.apk"))
        into(layout.buildDirectory.dir("outputs/$folder"))
        rename { outputName }
        val apkName = icgApkName
        val destino = layout.buildDirectory.file("outputs/$folder/$outputName").get().asFile
        doLast {
            val sha256 = MessageDigest.getInstance("SHA-256")
                .digest(destino.readBytes()).joinToString("") { "%02x".format(it) }
            File(destino.parentFile, "$outputName.sha256").writeText("$sha256  $outputName\n")
            logger.lifecycle("APK local $variant: $destino (apk_name='$apkName', versión=1, SHA-256=$sha256)")
        }
    }

}

fun registerApkSigning(taskName: String, variant: String, assembleTask: String) {
    tasks.register(taskName) {
        group = "permoda"
        description = "Re-firma el APK $variant con apksigner externo (v1+v2 verificables)."
        dependsOn(assembleTask)
        val storePass = project.findProperty("PERMODA_STORE_PASSWORD") as String?
        val keyPass = project.findProperty("PERMODA_KEY_PASSWORD") as String?
        val keyAlias = (project.findProperty("PERMODA_KEY_ALIAS") as String?) ?: "permodaview"
        val keystoreFile = (project.findProperty("PERMODA_STORE_FILE") as String?)?.let { file(it) }
        val apksignerFile = file("${android.sdkDirectory}/build-tools/37.0.0/apksigner.bat")
        val apkFile = layout.buildDirectory
            .file("outputs/apk/$variant/custvalidation-$variant.apk").get().asFile

        doLast {
            if (storePass == null || keyPass == null || keystoreFile == null) {
                throw GradleException(
                    "Faltan credenciales de firma. Definí PERMODA_STORE_FILE, " +
                        "PERMODA_STORE_PASSWORD, PERMODA_KEY_ALIAS y PERMODA_KEY_PASSWORD " +
                        "en ~/.gradle/gradle.properties. NO las pongas en este archivo."
                )
            }
            if (!apksignerFile.exists()) {
                throw GradleException("apksigner no encontrado en: $apksignerFile")
            }
            if (!apkFile.exists()) {
                throw GradleException("APK no encontrado: $apkFile — corré :app:$assembleTask primero")
            }
            val cmd = listOf(
                apksignerFile.absolutePath, "sign",
                "--ks", keystoreFile.absolutePath,
                "--ks-pass", "env:PERMODA_SIGN_STORE_PASS",
                "--ks-key-alias", keyAlias,
                "--key-pass", "env:PERMODA_SIGN_KEY_PASS",
                "--v1-signing-enabled", "true",
                "--v2-signing-enabled", "true",
                "--v3-signing-enabled", "false",
                apkFile.absolutePath
            )
            logger.lifecycle("Re-firmando v1+v2 con alias '$keyAlias'…")
            val signingProcess = ProcessBuilder(cmd).inheritIO()
            signingProcess.environment()["PERMODA_SIGN_STORE_PASS"] = storePass
            signingProcess.environment()["PERMODA_SIGN_KEY_PASS"] = keyPass
            val proc = signingProcess.start()
            proc.waitFor()
            if (proc.exitValue() != 0) {
                throw GradleException("apksigner sign falló con código ${proc.exitValue()}")
            }
            logger.lifecycle("✓ Re-firmado v1+v2: $apkFile")
        }
    }

}

registerApkSigning("refirmarV1V2", "release", "assembleRelease")
registerApkSigning("refirmarDevV1V2", "debug", "assembleDebug")
registerApkDelivery("apkParaCloudLicense", "refirmarV1V2", "release", "cloudlicense", "custvalidation.apk")
registerApkDelivery("apkDevParaAdb", "refirmarDevV1V2", "debug", "dev", "custvalidation-dev.apk")
