. "$PSScriptRoot\load-env.ps1"
[Console]::InputEncoding = [System.Text.UTF8Encoding]::new()
[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new()
Push-Location $projectRoot
try { & '.\clients\console-client\build\install\console-client\bin\console-client.bat' }
finally { Pop-Location }
