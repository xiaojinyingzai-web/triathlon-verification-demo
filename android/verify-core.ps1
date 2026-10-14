param([string]$JavaHome = '')
$ErrorActionPreference = 'Stop'
$projectRoot = $PSScriptRoot
if (-not $JavaHome) {
    $portableJdk = Get-ChildItem -LiteralPath (Join-Path $projectRoot '.tools') -Directory -Filter 'jdk-17*' -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($portableJdk) { $JavaHome = $portableJdk.FullName }
    elseif ($env:JAVA_HOME) { $JavaHome = $env:JAVA_HOME }
    else { throw 'JDK 17 missing. Supply -JavaHome or use the verified portable JDK.' }
}
$javacExe = Join-Path $JavaHome 'bin/javac.exe'
$javaExe = Join-Path $JavaHome 'bin/java.exe'
if (-not (Test-Path -LiteralPath $javacExe)) { throw 'javac missing' }
$classes = Join-Path $projectRoot 'core/build/self-check-classes'
$data = Join-Path $projectRoot 'core/build/self-check-data'
$evidence = Join-Path $projectRoot 'evidence'
New-Item -ItemType Directory -Force -Path $classes,$data,$evidence | Out-Null
$sources = @(Get-ChildItem -LiteralPath (Join-Path $projectRoot 'core/src') -Recurse -Filter '*.java' | Select-Object -ExpandProperty FullName)
& $javaExe -version 2>&1 | Tee-Object -FilePath (Join-Path $evidence 'java-version.txt')
if ($LASTEXITCODE -ne 0) { throw 'Java failed' }
& $javacExe '-J-Duser.language=en' --release 8 -encoding UTF-8 -Xlint:all -d $classes @sources 2>&1 | Tee-Object -FilePath (Join-Path $evidence 'javac.txt')
if ($LASTEXITCODE -ne 0) { throw 'Core compilation failed' }
& $javaExe -ea -cp $classes com.triathlon.prep.core.CoreSelfCheck $data 2>&1 | Tee-Object -FilePath (Join-Path $evidence 'core-self-check.txt')
if ($LASTEXITCODE -ne 0) { throw 'Core self-check failed' }
