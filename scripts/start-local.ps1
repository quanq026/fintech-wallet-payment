. "$PSScriptRoot\load-env.ps1"
Push-Location $projectRoot
try {
    $pids = @()
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
        return ($process | ConvertFrom-Json)
    }
    function Listening($port) { return [bool](Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue) }
    if (-not (Listening 56379)) { $pids += Start-ServiceProcess 'redis' (Join-Path $projectRoot 'work\redis\redis-server.exe') @('--port','56379','--bind','127.0.0.1') (Join-Path $projectRoot 'work\redis') }
    if (-not (Listening 9092)) { $pids += Start-ServiceProcess 'kafka' "$env:JAVA_HOME\bin\java.exe" @('-Xmx384m','-Dorg.slf4j.simpleLogger.logFile=System.out','-cp',"$projectRoot/work/kafka/lib/*",'kafka.Kafka',"$projectRoot/infrastructure/kafka/server.properties") $projectRoot }
    foreach ($service in @(@('fraud','services\fraud-service',8085),@('bank','sandbox\bank-sandbox',8086))) {
        if(-not (Listening $service[2])) { $pids += Start-ServiceProcess $service[0] (Join-Path $projectRoot 'work\venv\Scripts\python.exe') @('-m','uvicorn','app:app','--host','127.0.0.1','--port',$service[2]) (Join-Path $projectRoot $service[1]) }
    }
    foreach ($service in @(@('ledger-service',8081),@('transaction-service',8082),@('payment-service',8083),@('audit-service',8084),@('gateway-service',8080))) {
        if(-not (Listening $service[1])) {
            $jar = Get-ChildItem -LiteralPath (Join-Path $projectRoot "services\$($service[0])\build\libs") -Filter '*.jar' | Where-Object Name -notlike '*plain*' | Select-Object -First 1
            if(-not $jar) { throw "Build $($service[0]) first" }
            $pids += Start-ServiceProcess $service[0] "$env:JAVA_HOME\bin\java.exe" @('-Xmx256m','-jar',$jar.FullName) $projectRoot
        }
    }
    $pids | ConvertTo-Json | Set-Content -LiteralPath 'work\processes.json' -Encoding UTF8
    $ready=$false
    for($attempt=0;$attempt -lt 45;$attempt++) {
        $missingPorts=@(8080,8081,8082,8083,8084,8085,8086,9091,9092,56379) | Where-Object { -not (Listening $_) }
        if(-not $missingPorts){$ready=$true;break}
        Start-Sleep -Seconds 1
    }
    if(-not $ready){throw "Local services did not become ready; missing ports: $($missingPorts -join ', '). Check work logs."}
    foreach($servicePort in @(8080,8081,8082,8083,8084)) {
        $health=Invoke-RestMethod -Uri "http://127.0.0.1:$servicePort/actuator/health" -TimeoutSec 5
        if($health.status -ne 'UP'){throw "Service $servicePort is not healthy"}
    }
    Write-Output 'Local services are ready.'
} finally { Pop-Location }
