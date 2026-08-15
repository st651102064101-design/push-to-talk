param(
    [string]$Path = (Join-Path $PSScriptRoot ".env")
)

$ErrorActionPreference = "Stop"

function Read-PttEnvironment([string]$FilePath) {
    $values = @{}
    if (-not (Test-Path -LiteralPath $FilePath)) { return $values }
    Get-Content -LiteralPath $FilePath | ForEach-Object {
        if ($_ -match '^([^#][^=]*)=(.*)$') {
            $values[$matches[1].Trim()] = $matches[2].Trim()
        }
    }
    return $values
}

function Test-PttEnvironment([hashtable]$Values) {
    return $Values['PTT_USER_TOKEN'].Length -ge 32 `
        -and $Values['PTT_ADMIN_TOKEN'].Length -ge 32 `
        -and $Values['PTT_USER_TOKEN'] -ne $Values['PTT_ADMIN_TOKEN'] `
        -and $Values['PTT_DASHBOARD_USER'].Length -ge 1 `
        -and $Values['PTT_DASHBOARD_PASSWORD'].Length -ge 16
}

$current = Read-PttEnvironment $Path
if (Test-PttEnvironment $current) {
    Write-Host "Secure PTT credentials are ready."
    exit 0
}

if (Test-Path -LiteralPath $Path) {
    $backupPath = "$Path.invalid-$(Get-Date -Format 'yyyyMMdd-HHmmss')"
    Move-Item -LiteralPath $Path -Destination $backupPath
    Write-Host "Invalid credential file moved to $backupPath"
}

$rng = [Security.Cryptography.RandomNumberGenerator]::Create()
try {
    function New-Secret([int]$Size) {
        $bytes = New-Object byte[] $Size
        $rng.GetBytes($bytes)
        return (($bytes | ForEach-Object { $_.ToString('x2') }) -join '')
    }

    $userToken = [string](New-Secret 32)
    $adminToken = [string](New-Secret 32)
    $dashboardPassword = [string](New-Secret 16)
    $lines = [string[]]@(
        "PTT_USER_TOKEN=$userToken"
        "PTT_ADMIN_TOKEN=$adminToken"
        "PTT_DASHBOARD_USER=admin"
        "PTT_DASHBOARD_PASSWORD=$dashboardPassword"
    )
    [IO.File]::WriteAllLines($Path, $lines, [Text.Encoding]::ASCII)
    Write-Host "Created new secure PTT credentials."
}
finally {
    $rng.Dispose()
}
