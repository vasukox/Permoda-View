[CmdletBinding()]
param([switch]$GenerarRelease)

$ErrorActionPreference = 'Stop'
$projectAuditPath = Split-Path -Parent $PSScriptRoot
Push-Location -LiteralPath $projectAuditPath
try {
    # Solo tareas locales: no usa adb, no instala y no publica artefactos.
    & .\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug --offline --console=plain
    if ($LASTEXITCODE -ne 0) { throw 'La verificación local falló; revisar el informe Gradle.' }
    if ($GenerarRelease) {
        & .\gradlew.bat :app:apkParaCloudLicense --offline --console=plain
        if ($LASTEXITCODE -ne 0) { throw 'Falló la generación del APK local de release.' }
        Get-FileHash -LiteralPath 'app\build\outputs\cloudlicense\custvalidation.apk' -Algorithm SHA256
    }
} finally {
    Pop-Location
}
