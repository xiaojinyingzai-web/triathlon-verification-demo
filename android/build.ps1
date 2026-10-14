param(
    [ValidateSet('Core','Live','Simulation')][string]$Target = 'Core',
    [string]$JavaHome = '',
    [string]$AndroidSdk = ''
)
$ErrorActionPreference = 'Stop'
if (-not $JavaHome) {
    $portableJdk = Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot '.tools') -Directory -Filter 'jdk-17*' -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($portableJdk) { $JavaHome = $portableJdk.FullName }
    elseif ($env:JAVA_HOME) { $JavaHome = $env:JAVA_HOME }
    else { throw 'JDK 17 missing; supply -JavaHome' }
}
if (-not (Test-Path -LiteralPath (Join-Path $JavaHome 'bin/java.exe'))) { throw 'Java executable missing' }
$oldJavaHome = $env:JAVA_HOME
$oldGradleHome = $env:GRADLE_USER_HOME
$oldAndroidHome = $env:ANDROID_HOME
try {
    $env:JAVA_HOME = $JavaHome
    $env:GRADLE_USER_HOME = Join-Path $PSScriptRoot '.gradle-user'
    $gradleArgs = @('-p',$PSScriptRoot,'--console=plain')
    if ($Target -eq 'Core') { $gradleArgs += @('-PcoreOnly',':core:check') }
    else {
        if (-not $AndroidSdk) { $AndroidSdk = $env:ANDROID_HOME }
        if (-not $AndroidSdk -or -not (Test-Path -LiteralPath (Join-Path $AndroidSdk 'platforms/android-35/android.jar'))) {
            throw 'Android SDK platform 35 missing; user must review/accept SDK licences and provide -AndroidSdk'
        }
        $env:ANDROID_HOME = $AndroidSdk
        $gradleArgs += $(if ($Target -eq 'Live') { ':app:assembleLiveDebug' } else { ':app:assembleSimulationDebug' })
    }
    & (Join-Path $PSScriptRoot 'gradlew.bat') @gradleArgs
    if ($LASTEXITCODE -ne 0) { throw "Gradle $Target failed (exit $LASTEXITCODE)" }
} finally {
    $env:JAVA_HOME = $oldJavaHome
    $env:GRADLE_USER_HOME = $oldGradleHome
    $env:ANDROID_HOME = $oldAndroidHome
}
