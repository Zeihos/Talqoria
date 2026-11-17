param([switch]$Stopped)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$source = Join-Path $root 'server'
$destination = Join-Path $root 'backups'
if (-not $Stopped) {
    Write-Error 'STOP the Minecraft server first. Re-run: powershell -NoProfile -File .\BACKUP_OFFLINE_WINDOWS.ps1 -Stopped'
    exit 2
}
if (-not (Test-Path $source -PathType Container)) { throw 'server/ folder is missing' }
$valuable = @(Get-ChildItem -Path $source -Force | Where-Object { $_.Name -like 'world*' -or $_.Name -eq 'plugins' -or $_.Name -eq 'server.properties' })
if ($valuable.Count -eq 0) { throw 'No world, plugins, or server properties found in server/. Nothing useful to back up.' }
New-Item -ItemType Directory -Force -Path $destination | Out-Null
$name = 'talqoria-offline-' + (Get-Date).ToUniversalTime().ToString('yyyyMMdd-HHmmss') + '-' + [guid]::NewGuid().ToString('N').Substring(0,8) + '.zip'
$archive = Join-Path $destination $name
try {
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    [System.IO.Compression.ZipFile]::CreateFromDirectory($source, $archive, [System.IO.Compression.CompressionLevel]::Optimal, $false)
    $zip = [System.IO.Compression.ZipFile]::OpenRead($archive)
    try { if ($zip.Entries.Count -eq 0) { throw 'Archive is empty' } } finally { $zip.Dispose() }
    $hash = (Get-FileHash -Algorithm SHA256 $archive).Hash.ToLowerInvariant()
    $text = "$hash  $name" + [Environment]::NewLine
    Set-Content -LiteralPath ($archive + '.sha256.txt') -Value $text -Encoding UTF8
    Write-Host "TALQORIA offline backup created: $archive"
    Write-Host "SHA-256: $hash"
    Write-Host 'This backup was only valid if the Minecraft server was actually stopped.'
} catch {
    Remove-Item -LiteralPath $archive -Force -ErrorAction SilentlyContinue
    throw
}
