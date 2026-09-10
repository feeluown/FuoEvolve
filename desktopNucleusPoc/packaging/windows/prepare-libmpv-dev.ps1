param(
    [Parameter(Mandatory = $true)]
    [string]$OutputDir
)

$ErrorActionPreference = "Stop"

$lockFile = Join-Path $PSScriptRoot "..\..\..\desktopApp\packaging\native-deps.lock"
$entries = @{}
Get-Content $lockFile | ForEach-Object {
    $line = $_.Trim()
    if ($line -eq "" -or $line.StartsWith("#")) { return }
    $parts = $line -split "=", 2
    if ($parts.Length -eq 2) {
        $entries[$parts[0].Trim()] = $parts[1].Trim()
    }
}

$url = $entries["windows.x64.libmpv.url"]
$expectedSha = $entries["windows.x64.libmpv.sha256"]
if (-not $url -or -not $expectedSha) {
    throw "Windows libmpv URL/SHA is missing from $lockFile"
}

$sevenZip = (Get-Command 7z -ErrorAction Stop).Source
$workDir = Join-Path ([System.IO.Path]::GetTempPath()) ("fuoevolve-nucleus-libmpv-" + [Guid]::NewGuid())
$archive = Join-Path $workDir "libmpv.7z"
$extractDir = Join-Path $workDir "extract"
New-Item -ItemType Directory -Force -Path $extractDir | Out-Null

try {
    Write-Host "Downloading pinned Windows libmpv development bundle"
    Invoke-WebRequest -Uri $url -OutFile $archive
    $actualSha = (Get-FileHash -Path $archive -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($actualSha -ne $expectedSha.ToLowerInvariant()) {
        throw "Windows libmpv SHA-256 mismatch: expected $expectedSha, got $actualSha"
    }

    & $sevenZip x $archive "-o$extractDir" -y | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw "7-Zip failed to extract the Windows libmpv archive"
    }

    $clientHeader = Get-ChildItem -Path $extractDir -Recurse -File -Filter "client.h" |
        Where-Object { $_.FullName -match '[\\/]include[\\/]mpv[\\/]client\.h$' } |
        Select-Object -First 1
    $importLibrary = Get-ChildItem -Path $extractDir -Recurse -File -Filter "libmpv.dll.a" |
        Select-Object -First 1
    $dlls = Get-ChildItem -Path $extractDir -Recurse -File -Filter "*.dll"

    if (-not $clientHeader) { throw "Pinned Windows libmpv archive does not contain include/mpv/client.h" }
    if (-not $importLibrary) { throw "Pinned Windows libmpv archive does not contain libmpv.dll.a" }
    if (-not $dlls) { throw "Pinned Windows libmpv archive does not contain libmpv runtime DLLs" }

    Remove-Item -Path $OutputDir -Recurse -Force -ErrorAction SilentlyContinue
    New-Item -ItemType Directory -Force -Path (Join-Path $OutputDir "include\mpv") | Out-Null
    Copy-Item -Path $clientHeader.FullName -Destination (Join-Path $OutputDir "include\mpv\client.h")

    $headerRoot = $clientHeader.Directory.Parent.FullName
    Get-ChildItem -Path $headerRoot -File -Filter "*.h" | ForEach-Object {
        Copy-Item -Path $_.FullName -Destination (Join-Path $OutputDir "include\mpv\$($_.Name)") -Force
    }

    Copy-Item -Path $importLibrary.FullName -Destination (Join-Path $OutputDir "libmpv.dll.a")
    foreach ($dll in $dlls) {
        $destination = Join-Path $OutputDir $dll.Name
        if (Test-Path $destination) {
            $existingSha = (Get-FileHash -Path $destination -Algorithm SHA256).Hash
            $candidateSha = (Get-FileHash -Path $dll.FullName -Algorithm SHA256).Hash
            if ($existingSha -ne $candidateSha) {
                throw "Conflicting DLL name while flattening libmpv bundle: $($dll.Name)"
            }
        } else {
            Copy-Item -Path $dll.FullName -Destination $destination
        }
    }

    $supportedDll = @("libmpv-2.dll", "mpv-2.dll", "mpv.dll") |
        Where-Object { Test-Path (Join-Path $OutputDir $_) } |
        Select-Object -First 1
    if (-not $supportedDll) {
        throw "Prepared development bundle does not contain a supported libmpv DLL"
    }

    @"
Source: $url
SHA-256: $expectedSha
Purpose: Nucleus JNI compilation and bundled Windows libmpv runtime
"@ | Set-Content -Path (Join-Path $OutputDir "FUOEVOLVE_LIBMPV_SOURCE.txt") -Encoding UTF8

    Write-Host "Prepared Windows Nucleus libmpv development bundle at $OutputDir"
    Get-ChildItem -Path $OutputDir -Recurse | Select-Object FullName, Length
}
finally {
    Remove-Item -Path $workDir -Recurse -Force -ErrorAction SilentlyContinue
}
