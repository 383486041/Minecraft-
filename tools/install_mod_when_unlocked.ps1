param(
    [Parameter(Mandatory = $true)] [string] $OldJar,
    [Parameter(Mandatory = $true)] [string] $PendingJar,
    [Parameter(Mandatory = $true)] [string] $TargetJar,
    [Parameter(Mandatory = $true)] [string] $AllowedRoot,
    [Parameter(Mandatory = $true)] [string] $LogFile
)

$ErrorActionPreference = 'Stop'
$root = [System.IO.Path]::GetFullPath($AllowedRoot).TrimEnd('\') + '\'
foreach ($candidate in @($OldJar, $PendingJar, $TargetJar, $LogFile)) {
    $resolved = [System.IO.Path]::GetFullPath($candidate)
    if (-not $resolved.StartsWith($root, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "Path escapes allowed root: $resolved"
    }
}

$deadline = [DateTime]::UtcNow.AddHours(12)
while ([DateTime]::UtcNow -lt $deadline) {
    if (-not (Test-Path -LiteralPath $OldJar)) {
        break
    }

    try {
        $stream = [System.IO.File]::Open(
            $OldJar,
            [System.IO.FileMode]::Open,
            [System.IO.FileAccess]::ReadWrite,
            [System.IO.FileShare]::None
        )
        $stream.Dispose()
        Remove-Item -LiteralPath $OldJar
        break
    }
    catch [System.IO.IOException] {
        Start-Sleep -Seconds 2
    }
}

if (Test-Path -LiteralPath $OldJar) {
    "$(Get-Date -Format o) timeout waiting for old jar" | Set-Content -LiteralPath $LogFile -Encoding UTF8
    exit 2
}
if (-not (Test-Path -LiteralPath $PendingJar)) {
    "$(Get-Date -Format o) pending jar missing" | Set-Content -LiteralPath $LogFile -Encoding UTF8
    exit 3
}
if (Test-Path -LiteralPath $TargetJar) {
    Remove-Item -LiteralPath $TargetJar
}
Move-Item -LiteralPath $PendingJar -Destination $TargetJar
$hash = (Get-FileHash -Algorithm SHA256 -LiteralPath $TargetJar).Hash
"$(Get-Date -Format o) installed $TargetJar SHA256=$hash" | Set-Content -LiteralPath $LogFile -Encoding UTF8
