param(
    [ValidateSet('alice', 'bob', 'admin')]
    [string]$User = 'alice',
    [switch]$All
)

. "$PSScriptRoot\load-env.ps1"
if ($All) {
    $running = @(Get-CimInstance Win32_Process -Filter "Name = 'java.exe'" | Where-Object {
        $_.CommandLine -like "*$projectRoot*" -and $_.CommandLine -match 'ra\.consoleclient\.presentation\.WalletConsole'
    })
    foreach ($account in @('alice', 'bob', 'admin')) {
        if ($running | Where-Object { $_.CommandLine -match ("WalletConsole\s+" + $account + "(?:\s|$)") }) {
            Write-Output "Console $account is already open."
            continue
        }
        Start-Process -FilePath 'powershell.exe' -ArgumentList @('-NoProfile', '-ExecutionPolicy', 'Bypass',
                '-File', ('"' + $PSCommandPath + '"'), '-User', $account) -WorkingDirectory $projectRoot -WindowStyle Normal
    }
    return
}
[Console]::Title = 'FinTech - ' + $User.ToUpperInvariant()
[Console]::InputEncoding = [System.Text.UTF8Encoding]::new()
[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new()
Push-Location $projectRoot
try { & '.\clients\console-client\build\install\console-client\bin\console-client.bat' $User.ToLowerInvariant() }
finally { Pop-Location }
