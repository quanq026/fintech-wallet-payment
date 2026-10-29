$projectRoot = Split-Path $PSScriptRoot -Parent
$ownedJavaProcesses = Get-CimInstance Win32_Process -Filter "Name = 'java.exe'" | Where-Object {
    $_.CommandLine -like "*$projectRoot*" -and $_.CommandLine -match '-jar' -and $_.CommandLine -match '(ledger|transaction|payment|audit|gateway)-service'
}
foreach($ownedProcess in $ownedJavaProcesses) { Stop-Process -Id $ownedProcess.ProcessId -ErrorAction SilentlyContinue }
$processFile = Join-Path $projectRoot 'work\processes.json'
if(Test-Path -LiteralPath $processFile) {
    $savedProcesses = Get-Content -LiteralPath $processFile -Raw | ConvertFrom-Json
    foreach($savedProcess in $savedProcesses) {
        $liveProcess = Get-CimInstance Win32_Process -Filter "ProcessId = $($savedProcess.Id)" -ErrorAction SilentlyContinue
        if($liveProcess -and $liveProcess.CommandLine -like "*$projectRoot*") {
            foreach($child in (Get-CimInstance Win32_Process -Filter "ParentProcessId = $($savedProcess.Id)" -ErrorAction SilentlyContinue)) { Stop-Process -Id $child.ProcessId -ErrorAction SilentlyContinue }
            Stop-Process -Id $savedProcess.Id -ErrorAction SilentlyContinue
        }
    }
}
