param(
    [ValidateSet('build', 'test', 'client', 'shaders', 'server', 'gametest')]
    [string]$Task = 'build'
)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
if (-not $env:JAVA_HOME) {
    $localJdk = Get-ChildItem -LiteralPath (Join-Path $projectRoot '.tools/java') -Directory -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($localJdk) { $env:JAVA_HOME = $localJdk.FullName }
}
if ($env:JAVA_HOME) { $env:PATH = (Join-Path $env:JAVA_HOME 'bin') + ';' + $env:PATH }
if (-not (Get-Command java -ErrorAction SilentlyContinue)) { throw 'Java 17 requis : définir JAVA_HOME vers un JDK 17.' }
$gradleTask = switch ($Task) {
    'client' { 'runClient' }
    'shaders' { 'runClient' }
    'server' { 'runServer' }
    'gametest' { 'runGameTestServer' }
    default { $Task }
}
Push-Location -LiteralPath $projectRoot
try {
    $extra = if ($Task -eq 'shaders') { @('-Pshaders') } else { @() }
    & (Join-Path $projectRoot 'gradlew.bat') $gradleTask @extra '--console=plain'
    exit $LASTEXITCODE
} finally { Pop-Location }
