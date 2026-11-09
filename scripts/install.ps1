# Installs the gekko CLI and its Android system runner from a release of jakala-germany/gekko, on Windows (x64).
#
#   scripts\install.ps1 [-Version VERSION|latest]   install (default: the latest release)
#   scripts\install.ps1 -Web BROWSERS               also install Playwright and the browsers for web tests (needs Node.js 18+)
#                                                   BROWSERS: comma separated chromium, firefox, webkit, e.g. chromium,firefox
#   scripts\install.ps1 -Uninstall                  remove the CLI, the runners and the web files again
#
# Remote (pin the version in the URL and in the argument; works in Windows PowerShell 5.1 and PowerShell 7):
#   & ([scriptblock]::Create((irm https://raw.githubusercontent.com/jakala-germany/gekko/1.0.0/scripts/install.ps1))) -Version 1.0.0
#
# The CLI goes to $env:GEKKO_BIN_DIR (default ~\.gekko\bin) and that folder is added to the PATH of the user, the runner
# to $env:GEKKO_HOME\runners (default ~\.gekko\runners); gekko finds it there without any setting. Playwright for web
# tests goes to $env:GEKKO_HOME\web (the browsers it downloads live in the Playwright cache of the user). Every download
# is checked against the SHA256SUMS of the release. With GEKKO_VERIFY_ATTESTATION=1 and the GitHub CLI installed the
# build provenance is verified as well. GEKKO_RELEASE_BASE_URL replaces https://github.com/jakala-germany/gekko/releases
# (for tests). iOS simulators need a Mac; Windows on ARM64 is not supported yet.
param(
    [string]$Version = 'latest',
    [string]$Web = '',
    [switch]$Uninstall,
    [switch]$Help
)

$ErrorActionPreference = 'Stop'
# Invoke-WebRequest is slow with its progress bar in Windows PowerShell 5.1
$ProgressPreference = 'SilentlyContinue'

function Fail([string]$Message) {
    throw "install.ps1: $Message"
}

if ($Help) {
    Get-Content -LiteralPath $PSCommandPath -ErrorAction SilentlyContinue | Select-Object -First 17 | ForEach-Object { $_ -replace '^# ?', '' }
    return
}

# Windows PowerShell 5.1 speaks TLS 1.0 unless told otherwise, and GitHub does not
[Net.ServicePointManager]::SecurityProtocol = [Net.ServicePointManager]::SecurityProtocol -bor [Net.SecurityProtocolType]::Tls12

$BaseUrl = if ($env:GEKKO_RELEASE_BASE_URL) { $env:GEKKO_RELEASE_BASE_URL } else { 'https://github.com/jakala-germany/gekko/releases' }
$UserHome = if ($env:USERPROFILE) { $env:USERPROFILE } else { $HOME }
$GekkoDir = if ($env:GEKKO_HOME) { $env:GEKKO_HOME } else { Join-Path $UserHome '.gekko' }
$BinDir = if ($env:GEKKO_BIN_DIR) { $env:GEKKO_BIN_DIR } else { Join-Path $GekkoDir 'bin' }
$RunnersDir = Join-Path $GekkoDir 'runners'
$Asset = 'gekko-windows-x64.exe'
$RunnerAsset = 'gekko-system-runner.apk'

function Same-Directory([string]$Left, [string]$Right) {
    return $Left.TrimEnd('\', '/') -ieq $Right.TrimEnd('\', '/')
}

# The user PATH is edited in the registry: [Environment]::SetEnvironmentVariable would store it as a plain string and
# thereby break entries like %USERPROFILE%\bin
function Edit-UserPath([string]$Directory, [bool]$Add) {
    $key = [Microsoft.Win32.Registry]::CurrentUser.OpenSubKey('Environment', $true)
    try {
        $current = [string]$key.GetValue('Path', '', [Microsoft.Win32.RegistryValueOptions]::DoNotExpandEnvironmentNames)
        $entries = @($current -split ';' | Where-Object { $_ -ne '' })
        $others = @($entries | Where-Object { -not (Same-Directory $_ $Directory) })
        $found = $others.Count -ne $entries.Count
        if ($Add -and -not $found) {
            $key.SetValue('Path', (($entries + $Directory) -join ';'), [Microsoft.Win32.RegistryValueKind]::ExpandString)
            [Environment]::SetEnvironmentVariable('GEKKO_PATH_REFRESH', '1', 'User')
            [Environment]::SetEnvironmentVariable('GEKKO_PATH_REFRESH', $null, 'User')
            return $true
        }
        if (-not $Add -and $found) {
            $key.SetValue('Path', ($others -join ';'), [Microsoft.Win32.RegistryValueKind]::ExpandString)
            [Environment]::SetEnvironmentVariable('GEKKO_PATH_REFRESH', '1', 'User')
            [Environment]::SetEnvironmentVariable('GEKKO_PATH_REFRESH', $null, 'User')
            return $true
        }
        return $false
    }
    finally {
        $key.Close()
    }
}

if ($Uninstall) {
    # Browsers of web sessions that still run are closed first, with everything they started
    $sessions = Join-Path $GekkoDir 'web\sessions'
    if (Test-Path -LiteralPath $sessions) {
        foreach ($session in Get-ChildItem -LiteralPath $sessions -Filter '*.json') {
            $text = Get-Content -LiteralPath $session.FullName -Raw
            if ($text -match '"pid"\s*:\s*(\d+)') {
                & taskkill.exe /PID $Matches[1] /T /F 2>&1 | Out-Null
            }
        }
    }
    Remove-Item -LiteralPath (Join-Path $BinDir 'gekko.exe') -Force -ErrorAction SilentlyContinue
    Remove-Item -LiteralPath $RunnersDir, (Join-Path $GekkoDir 'web') -Recurse -Force -ErrorAction SilentlyContinue
    if (Test-Path -LiteralPath $BinDir) {
        if (@(Get-ChildItem -LiteralPath $BinDir -Force).Count -eq 0) {
            Remove-Item -LiteralPath $BinDir -Force
        }
    }
    [void](Edit-UserPath $BinDir $false)
    Write-Host "Removed $BinDir\gekko.exe, $RunnersDir and $GekkoDir\web (the token file in $GekkoDir is kept)"
    return
}

if ($Web -ne '') {
    if (-not (Get-Command node -ErrorAction SilentlyContinue)) {
        Fail '-Web needs Node.js 18 or newer (https://nodejs.org); nothing was installed'
    }
}

$architecture = if ($env:PROCESSOR_ARCHITEW6432) { $env:PROCESSOR_ARCHITEW6432 } else { $env:PROCESSOR_ARCHITECTURE }
if ($architecture -ne 'AMD64') {
    Fail "unsupported platform Windows $architecture: gekko runs on Windows x64 (and on macOS arm64 and Linux x64)"
}

if ($Version -eq 'latest') {
    try {
        $response = Invoke-WebRequest -Uri "$BaseUrl/latest" -Method Head -UseBasicParsing
        # Windows PowerShell 5.1 has the final address in ResponseUri, PowerShell 7 in the request of the response
        $final = if ($response.BaseResponse.ResponseUri) { $response.BaseResponse.ResponseUri } else { $response.BaseResponse.RequestMessage.RequestUri }
        $Version = ($final.AbsoluteUri -split '/')[-1]
    }
    catch {
        Fail "could not look up the latest release: $($_.Exception.Message)"
    }
}
$Version = $Version -replace '^v', ''
if ($Version -notmatch '^\d+\.\d+\.\d+$') {
    Fail "'$Version' is not a release version like 1.0.0"
}
$DownloadUrl = "$BaseUrl/download/$Version"

$work = Join-Path ([IO.Path]::GetTempPath()) ("gekko-install-" + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $work | Out-Null
try {
    Write-Host "Installing gekko $Version"
    try {
        Invoke-WebRequest -Uri "$DownloadUrl/SHA256SUMS" -OutFile (Join-Path $work 'SHA256SUMS') -UseBasicParsing
    }
    catch {
        Fail "no release $Version found at $DownloadUrl"
    }
    $sums = Get-Content -LiteralPath (Join-Path $work 'SHA256SUMS')
    foreach ($name in @($Asset, $RunnerAsset)) {
        try {
            Invoke-WebRequest -Uri "$DownloadUrl/$name" -OutFile (Join-Path $work $name) -UseBasicParsing
        }
        catch {
            Fail "downloading $name failed: $($_.Exception.Message)"
        }
        # A line is "<hash>  <name>" (a binary file has a * in front of its name); a file that is not listed fails
        $expected = $null
        foreach ($line in $sums) {
            $parts = $line.Trim() -split '\s+', 2
            if ($parts.Count -eq 2 -and ($parts[1] -replace '^\*', '') -eq $name) {
                $expected = $parts[0].ToLowerInvariant()
            }
        }
        if (-not $expected) {
            Fail "SHA256SUMS of the release has no entry for $name"
        }
        $actual = (Get-FileHash -LiteralPath (Join-Path $work $name) -Algorithm SHA256).Hash.ToLowerInvariant()
        if ($actual -ne $expected) {
            Fail "$name does not match SHA256SUMS; nothing was installed"
        }
    }

    if ($env:GEKKO_VERIFY_ATTESTATION -eq '1') {
        if (-not (Get-Command gh -ErrorAction SilentlyContinue)) {
            Fail 'GEKKO_VERIFY_ATTESTATION=1 needs the GitHub CLI (gh)'
        }
        foreach ($name in @($Asset, $RunnerAsset)) {
            & gh attestation verify (Join-Path $work $name) --repo jakala-germany/gekko 2>&1 | Out-Null
            if ($LASTEXITCODE -ne 0) {
                Fail "provenance of $name could not be verified"
            }
        }
        Write-Host 'Build provenance verified'
    }

    New-Item -ItemType Directory -Path $BinDir, $RunnersDir -Force | Out-Null
    try {
        Copy-Item -LiteralPath (Join-Path $work $Asset) -Destination (Join-Path $BinDir 'gekko.exe') -Force
    }
    catch {
        Fail "cannot replace $BinDir\gekko.exe (is gekko running? stop it and run this again): $($_.Exception.Message)"
    }
    Get-ChildItem -LiteralPath $RunnersDir -Force | Remove-Item -Recurse -Force
    Copy-Item -LiteralPath (Join-Path $work $RunnerAsset) -Destination $RunnersDir -Force
}
finally {
    Remove-Item -LiteralPath $work -Recurse -Force -ErrorAction SilentlyContinue
}

Write-Host "Installed gekko $Version to $BinDir\gekko.exe and the runner to $RunnersDir"
if ($Web -ne '') {
    foreach ($browser in ($Web -split ',')) {
        $env:GEKKO_HOME = $GekkoDir
        & (Join-Path $BinDir 'gekko.exe') install --platform web -s $browser.Trim()
        if ($LASTEXITCODE -ne 0) {
            Fail "gekko install --platform web -s $browser failed"
        }
    }
}

if (Edit-UserPath $BinDir $true) {
    Write-Host "Added $BinDir to your PATH; open a new terminal to use gekko"
}
$env:Path = "$env:Path;$BinDir"
if ($Web -eq '') {
    Write-Host 'For web tests (Node.js 18+): gekko install --platform web -s chromium   (or run this script again with -Web)'
}
