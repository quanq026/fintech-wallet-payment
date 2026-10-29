. "$PSScriptRoot\load-env.ps1"
. "$PSScriptRoot\stop-local.ps1"
Push-Location $projectRoot
try {
    & '.\gradlew.bat' test ':ledger-service:bootJar' ':transaction-service:bootJar' ':payment-service:bootJar' ':audit-service:bootJar' ':gateway-service:bootJar' ':console-client:installDist' '--console=plain'
    if($LASTEXITCODE -ne 0) { throw 'Build failed' }
} finally { Pop-Location }
