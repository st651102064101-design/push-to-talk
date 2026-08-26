param(
    [string]$Path = (Join-Path $PSScriptRoot ".env"),
    [switch]$RotateDashboardPassword
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

function New-PttSecret([int]$Size) {
    $generator = [Security.Cryptography.RandomNumberGenerator]::Create()
    try {
        $bytes = New-Object byte[] $Size
        $generator.GetBytes($bytes)
        return (($bytes | ForEach-Object { $_.ToString('x2') }) -join '')
    }
    finally {
        $generator.Dispose()
    }
}

$current = Read-PttEnvironment $Path
if ($RotateDashboardPassword) {
    if (-not (Test-PttEnvironment $current)) {
        throw "Cannot rotate the Dashboard password because the credential file is missing or invalid."
    }

    $newDashboardPassword = New-PttSecret 16
    $lines = [string[]]@(
        "PTT_USER_TOKEN=$($current['PTT_USER_TOKEN'])"
        "PTT_ADMIN_TOKEN=$($current['PTT_ADMIN_TOKEN'])"
        "PTT_DASHBOARD_USER=$($current['PTT_DASHBOARD_USER'])"
        "PTT_DASHBOARD_PASSWORD=$newDashboardPassword"
    )
    $temporaryPath = "$Path.rotating"
    try {
        [IO.File]::WriteAllLines($temporaryPath, $lines, [Text.Encoding]::ASCII)
        Move-Item -LiteralPath $temporaryPath -Destination $Path -Force
    }
    finally {
        if (Test-Path -LiteralPath $temporaryPath) {
            Remove-Item -LiteralPath $temporaryPath -Force
        }
    }
    Write-Host "Dashboard password rotated. Restart the PTT Server to apply it."
    exit 0
}

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
