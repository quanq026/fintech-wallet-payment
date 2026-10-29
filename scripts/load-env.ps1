$projectRoot = Split-Path $PSScriptRoot -Parent
$configFile = Join-Path $projectRoot 'work\local.env'
if (-not (Test-Path -LiteralPath $configFile)) { throw 'Missing work/local.env. Run local setup first.' }
foreach ($configLine in Get-Content -LiteralPath $configFile) {
    if ($configLine -match '^([A-Z_]+)=(.*)$') { [Environment]::SetEnvironmentVariable($matches[1],$matches[2],'Process') }
}
if (-not $env:JAVA_HOME -or -not (Test-Path -LiteralPath ($env:JAVA_HOME + '\bin\java.exe') -ErrorAction SilentlyContinue)) {
    $jdkPath = Get-Command javac.exe -ErrorAction Stop
    $env:JAVA_HOME = Split-Path (Split-Path $jdkPath.Source -Parent) -Parent
}
