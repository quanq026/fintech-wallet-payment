param(
    [ValidateSet('alice', 'bob', 'admin')]
    [string]$User = 'alice'
)

. "$PSScriptRoot\load-env.ps1"
[Console]::InputEncoding = [System.Text.UTF8Encoding]::new()
[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new()
Push-Location $projectRoot
try { & '.\clients\console-client\build\install\console-client\bin\console-client.bat' $User.ToLowerInvariant() }
finally { Pop-Location }
