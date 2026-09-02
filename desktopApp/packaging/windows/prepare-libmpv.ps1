param(
    [Parameter(Mandatory = $true)]
    [string]$OutputDir
)

$ErrorActionPreference = "Stop"

$lockFile = Join-Path $PSScriptRoot "..\native-deps.lock"
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
$workDir = Join-Path ([System.IO.Path]::GetTempPath()) ("fuoevolve-libmpv-" + [Guid]::NewGuid())
$archive = Join-Path $workDir "libmpv.7z"
$extractDir = Join-Path $workDir "extract"
New-Item -ItemType Directory -Force -Path $extractDir | Out-Null

try {
    Write-Host "Downloading pinned Windows libmpv bundle"
    Invoke-WebRequest -Uri $url -OutFile $archive
    $actualSha = (Get-FileHash -Path $archive -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($actualSha -ne $expectedSha.ToLowerInvariant()) {
        throw "Windows libmpv SHA-256 mismatch: expected $expectedSha, got $actualSha"
    }

    & $sevenZip x $archive "-o$extractDir" -y | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw "7-Zip failed to extract the Windows libmpv archive"
    }

    $dlls = Get-ChildItem -Path $extractDir -Recurse -File -Filter "*.dll"
    if (-not $dlls) {
        throw "Pinned Windows libmpv archive did not contain any DLL files"
    }

    New-Item -ItemType Directory -Force -Path $OutputDir | Out-Null
    foreach ($dll in $dlls) {
        $destination = Join-Path $OutputDir $dll.Name
        if (Test-Path $destination) {
            $existingSha = (Get-FileHash -Path $destination -Algorithm SHA256).Hash
            $candidateSha = (Get-FileHash -Path $dll.FullName -Algorithm SHA256).Hash
            if ($existingSha -ne $candidateSha) {
                throw "Conflicting DLL name while flattening libmpv bundle: $($dll.Name)"
            }
            continue
        }
        Copy-Item -Path $dll.FullName -Destination $destination
    }

    $expectedNames = @("mpv-2.dll", "libmpv-2.dll", "mpv.dll")
    if (-not ($expectedNames | Where-Object { Test-Path (Join-Path $OutputDir $_) })) {
        throw "Pinned archive did not contain a supported libmpv DLL name: $($expectedNames -join ', ')"
    }

    @"
Source: $url
SHA-256: $expectedSha
Purpose: bundled libmpv runtime for the FuoEvolve Windows desktop package
"@ | Set-Content -Path (Join-Path $OutputDir "FUOEVOLVE_LIBMPV_SOURCE.txt") -Encoding UTF8

    Write-Host "Prepared Windows libmpv bundle at $OutputDir"
    Get-ChildItem $OutputDir | Select-Object Name, Length
}
finally {
    Remove-Item -Path $workDir -Recurse -Force -ErrorAction SilentlyContinue
}
