# Runs install.ps1 against a release that is made of the freshly built binary and served from this machine: the checksum
# check, the installation, the PATH entry and the removal. Used by CI on Windows, works locally too.
# Needs: ./gradlew :cli:linkReleaseExecutableMingwX64 and python on the PATH.
param(
    [string]$Binary = 'cli\build\bin\mingwX64\releaseExecutable\gekko.exe',
    [int]$Port = 8791
)

$ErrorActionPreference = 'Stop'
$script = Join-Path $PSScriptRoot 'install.ps1'
$root = Join-Path ([IO.Path]::GetTempPath()) ('gekko-install-test-' + [Guid]::NewGuid().ToString('N'))
$release = Join-Path $root 'site\download\9.9.9'
New-Item -ItemType Directory -Path $release | Out-Null
Copy-Item -LiteralPath $Binary -Destination (Join-Path $release 'gekko-windows-x64.exe')
Set-Content -LiteralPath (Join-Path $release 'gekko-system-runner.apk') -Value 'not a real apk'

function Write-Sums([string]$Override) {
    $lines = foreach ($name in 'gekko-windows-x64.exe', 'gekko-system-runner.apk') {
        $hash = (Get-FileHash -LiteralPath (Join-Path $release $name) -Algorithm SHA256).Hash.ToLowerInvariant()
        if ($Override -and $name -eq 'gekko-system-runner.apk') { $hash = $Override }
        "$hash  $name"
    }
    Set-Content -LiteralPath (Join-Path $release 'SHA256SUMS') -Value $lines
}

function Run-Installer([string[]]$Arguments) {
    & powershell.exe -NoProfile -ExecutionPolicy Bypass -File $script @Arguments
    return $LASTEXITCODE
}

function Expect([bool]$Condition, [string]$Message) {
    if (-not $Condition) { throw "install test failed: $Message" }
}

function UserPath() {
    $key = [Microsoft.Win32.Registry]::CurrentUser.OpenSubKey('Environment')
    try { return [string]$key.GetValue('Path', '', [Microsoft.Win32.RegistryValueOptions]::DoNotExpandEnvironmentNames) }
    finally { $key.Close() }
}

$server = Start-Process python -ArgumentList '-m', 'http.server', "$Port", '--bind', '127.0.0.1', '--directory', (Join-Path $root 'site') -PassThru -WindowStyle Hidden
$oldPath = UserPath
try {
    Start-Sleep -Seconds 3
    $env:GEKKO_RELEASE_BASE_URL = "http://127.0.0.1:$Port"
    $env:GEKKO_HOME = Join-Path $root 'home'
    $exe = Join-Path $env:GEKKO_HOME 'bin\gekko.exe'
    $apk = Join-Path $env:GEKKO_HOME 'runners\gekko-system-runner.apk'

    # A download that does not match its checksum installs nothing
    Write-Sums ('0' * 64)
    Expect ((Run-Installer @('-Version', '9.9.9')) -ne 0) 'a wrong checksum must fail'
    Expect (-not (Test-Path -LiteralPath $exe)) 'nothing may be installed after a wrong checksum'

    Write-Sums ''
    Expect ((Run-Installer @('-Version', '9.9.9')) -eq 0) 'the installation failed'
    Expect (Test-Path -LiteralPath $exe) 'gekko.exe is missing'
    Expect (Test-Path -LiteralPath $apk) 'the system runner is missing'
    $version = & $exe --version
    Expect ($LASTEXITCODE -eq 0) 'the installed gekko.exe does not run'
    Write-Host "gekko.exe answers: $version"
    Expect ((UserPath).Contains((Join-Path $env:GEKKO_HOME 'bin'))) 'the bin folder is not on the PATH of the user'

    # Running it again replaces everything and adds nothing to the PATH twice
    Expect ((Run-Installer @('-Version', '9.9.9')) -eq 0) 'the second installation failed'
    $entries = @((UserPath) -split ';' | Where-Object { $_ -eq (Join-Path $env:GEKKO_HOME 'bin') })
    Expect ($entries.Count -eq 1) 'the bin folder is on the PATH more than once'

    Expect ((Run-Installer @('-Uninstall')) -eq 0) 'the removal failed'
    Expect (-not (Test-Path -LiteralPath $exe)) 'gekko.exe is still there'
    Expect (-not (Test-Path -LiteralPath (Join-Path $env:GEKKO_HOME 'runners'))) 'the runners are still there'
    Expect (-not (UserPath).Contains((Join-Path $env:GEKKO_HOME 'bin'))) 'the PATH entry is still there'
    Write-Host 'install.ps1 passed'
}
finally {
    Stop-Process -Id $server.Id -Force -ErrorAction SilentlyContinue
    $key = [Microsoft.Win32.Registry]::CurrentUser.OpenSubKey('Environment', $true)
    try { $key.SetValue('Path', $oldPath, [Microsoft.Win32.RegistryValueKind]::ExpandString) } finally { $key.Close() }
    Remove-Item -LiteralPath $root -Recurse -Force -ErrorAction SilentlyContinue
}
