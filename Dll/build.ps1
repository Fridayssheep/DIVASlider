param(
    [string] $Configuration = "Release",
    [string] $OutDir = "build",
    [switch] $AllowNonX64
)

$ErrorActionPreference = "Stop"

$cl = Get-Command cl.exe -ErrorAction SilentlyContinue
if ($null -eq $cl) {
    throw "cl.exe was not found. Run this from a Visual Studio Developer PowerShell."
}

$targetArch = $env:VSCMD_ARG_TGT_ARCH
if (-not $targetArch) {
    $targetArch = $env:Platform
}

if (-not $AllowNonX64 -and $targetArch -and $targetArch -notin @("x64", "amd64", "X64", "AMD64")) {
    throw "This DLL must normally be built as x64 for DIVA/divahook. Current target architecture is '$targetArch'. Open 'x64 Native Tools Command Prompt/PowerShell for VS' and rerun .\build.ps1, or pass -AllowNonX64 intentionally."
}

New-Item -ItemType Directory -Force -Path $OutDir | Out-Null

$detoursRoot = Join-Path $PSScriptRoot "..\Resource\PDloader\Code\source-code\dependencies\detours"
$detoursInclude = Join-Path $detoursRoot "include"
$detoursLib = Join-Path $detoursRoot "lib\detours.lib"

if (-not (Test-Path $detoursInclude) -or -not (Test-Path $detoursLib)) {
    throw "Detours dependency was not found under $detoursRoot"
}

$commonFlags = @(
    "/nologo",
    "/LD",
    "/W4",
    "/DWIN32_LEAN_AND_MEAN",
    "/Iinclude",
    "/I$detoursInclude"
)

if ($Configuration -ieq "Debug") {
    $commonFlags += @("/Od", "/Zi", "/D_DEBUG", "/MTd")
} else {
    $commonFlags += @("/O2", "/DNDEBUG", "/MT")
}

$sources = @(
    "src\divaslider_dll.c",
    "src\divaslider_log.c",
    "src\divaslider_pdloader.c",
    "src\divaslider_shm.c"
)

& cl.exe @commonFlags @sources "/Fe:$OutDir\divaslider.dll" "/link" "/DEF:divaslider.def" "/MACHINE:X64" $detoursLib

if ($LASTEXITCODE -ne 0) {
    exit $LASTEXITCODE
}

Write-Host "Built $OutDir\divaslider.dll"
