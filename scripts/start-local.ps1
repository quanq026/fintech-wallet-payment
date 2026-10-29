$ErrorActionPreference = 'Stop'
. "$PSScriptRoot\load-env.ps1"
Push-Location $projectRoot
try {
    Write-Output 'Starting local services...'
    $pids = @()
    $startedProcesses = [System.Collections.Generic.List[object]]::new()
    $savedProcessFile = Join-Path $projectRoot 'work\processes.json'
    if(Test-Path -LiteralPath $savedProcessFile) {
        $savedProcesses = Get-Content -LiteralPath $savedProcessFile -Raw | ConvertFrom-Json
        foreach($savedProcess in $savedProcesses) {
            if($savedProcess.Id -and (Get-CimInstance Win32_Process -Filter "ProcessId = $($savedProcess.Id)" -ErrorAction SilentlyContinue)) { $pids += $savedProcess }
        }
    }
    function Start-ServiceProcess($name,$file,$arguments,$directory) {
        $process = & (Join-Path $projectRoot 'work\venv\Scripts\python.exe') (Join-Path $projectRoot 'scripts\start-process.py') $projectRoot $name $file $directory @arguments
        if($LASTEXITCODE -ne 0) { throw "Could not start $name" }
        $startedProcess = $process | ConvertFrom-Json
        $startedProcesses.Add($startedProcess)
        return $startedProcess
    }
    function Get-ListeningPorts { [System.Net.NetworkInformation.IPGlobalProperties]::GetIPGlobalProperties().GetActiveTcpListeners().Port }
    $listeningPorts = @(Get-ListeningPorts)
    if ($listeningPorts -notcontains 56379) { $pids += Start-ServiceProcess 'redis' (Join-Path $projectRoot 'work\redis\redis-server.exe') @('--port','56379','--bind','127.0.0.1') (Join-Path $projectRoot 'work\redis') }
    if ($listeningPorts -notcontains 9092) { $pids += Start-ServiceProcess 'kafka' "$env:JAVA_HOME\bin\java.exe" @('-Xmx384m','-Dorg.slf4j.simpleLogger.logFile=System.out','-cp',"$projectRoot/work/kafka/lib/*",'kafka.Kafka',"$projectRoot/infrastructure/kafka/server.properties") $projectRoot }
    foreach ($service in @(@('fraud','services\fraud-service',8085),@('bank','sandbox\bank-sandbox',8086))) {
        if($listeningPorts -notcontains $service[2]) { $pids += Start-ServiceProcess $service[0] (Join-Path $projectRoot 'work\venv\Scripts\python.exe') @('-m','uvicorn','app:app','--host','127.0.0.1','--port',$service[2]) (Join-Path $projectRoot $service[1]) }
    }
    foreach ($service in @(@('ledger-service',8081),@('transaction-service',8082),@('payment-service',8083),@('audit-service',8084),@('gateway-service',8080))) {
        if($listeningPorts -notcontains $service[1]) {
            $jar = Get-ChildItem -LiteralPath (Join-Path $projectRoot "services\$($service[0])\build\libs") -Filter '*.jar' | Where-Object Name -notlike '*plain*' | Select-Object -First 1
            if(-not $jar) { throw "Build $($service[0]) first" }
            $pids += Start-ServiceProcess $service[0] "$env:JAVA_HOME\bin\java.exe" @('-Xmx256m','-jar',$jar.FullName) $projectRoot
        }
    }
    $pids | ConvertTo-Json | Set-Content -LiteralPath 'work\processes.json' -Encoding UTF8
    $ready=$false
    $readyTimer = [System.Diagnostics.Stopwatch]::StartNew()
    $previousMissingPorts = ''
    while($readyTimer.Elapsed.TotalSeconds -lt 45) {
        foreach($startedProcess in $startedProcesses) {
            if(-not (Get-Process -Id $startedProcess.Id -ErrorAction SilentlyContinue)) {
                throw "$($startedProcess.Name) exited before readiness. Check $projectRoot\work\$($startedProcess.Name).log and $projectRoot\work\$($startedProcess.Name)-error.log."
            }
        }
        $listeningPorts = @(Get-ListeningPorts)
        $missingPorts=@(8080,8081,8082,8083,8084,8085,8086,9091,9092,56379) | Where-Object { $listeningPorts -notcontains $_ }
        if(-not $missingPorts){$ready=$true;break}
        $pendingPorts = $missingPorts -join ', '
        if($pendingPorts -ne $previousMissingPorts) {
            Write-Output "Waiting for local ports: $pendingPorts"
            $previousMissingPorts = $pendingPorts
        }
        Start-Sleep -Seconds 1
    }
    if(-not $ready){throw "Local services did not become ready; missing ports: $($missingPorts -join ', '). Check work logs."}
    foreach($servicePort in @(8080,8081,8082,8083,8084)) {
        $health=Invoke-RestMethod -Uri "http://127.0.0.1:$servicePort/actuator/health" -TimeoutSec 5
        if($health.status -ne 'UP'){throw "Service $servicePort is not healthy"}
    }
    Write-Output 'Local services are ready.'
} finally { Pop-Location }
