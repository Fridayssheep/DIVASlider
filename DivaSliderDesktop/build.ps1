param(
    [switch] $SkipWails
)

$ErrorActionPreference = "Stop"

$root = Split-Path -Parent $PSScriptRoot
$vigemClient = Join-Path $root "Resource\ViGEmBus\sdk\bin\release\x64\ViGEmClient.dll"
$localClient = Join-Path $PSScriptRoot "ViGEmClient.dll"
$outputClient = Join-Path $PSScriptRoot "build\bin\ViGEmClient.dll"

if (-not (Test-Path -LiteralPath $vigemClient)) {
    throw "ViGEmClient.dll was not found at '$vigemClient'. Build Resource\ViGEmBus\sdk\src\ViGEmClient.vcxproj first."
}

Copy-Item -LiteralPath $vigemClient -Destination $localClient -Force

if (-not $SkipWails) {
    Push-Location $PSScriptRoot
    try {
        wails build
    } finally {
        Pop-Location
    }
}

New-Item -ItemType Directory -Force -Path (Split-Path -Parent $outputClient) | Out-Null
Copy-Item -LiteralPath $vigemClient -Destination $outputClient -Force

Write-Host "Copied ViGEmClient.dll to:"
Write-Host "  $localClient"
Write-Host "  $outputClient"
