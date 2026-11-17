# Download verified Apache Maven only into TALQORIA/.tools
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
$version = '3.9.16'
$checksum = 'ed41650d42485cfc243fad22158caf9cbb5dc408ce7a09ddb94dd42a019de929ca43065bfa450612cf12bf78b5cafa3884b96c090de326ff590448c933454af3'
$projectDir = Split-Path -Parent $PSScriptRoot
$toolsDir = Join-Path $projectDir '.tools'
$installDir = Join-Path $toolsDir "apache-maven-$version"
$mavenExe = Join-Path $installDir 'bin\mvn.cmd'
$archivePath = Join-Path $toolsDir "apache-maven-$version-bin.zip"
$downloadUrl = "https://archive.apache.org/dist/maven/maven-3/$version/binaries/apache-maven-$version-bin.zip"
try {
    if (Test-Path -LiteralPath $mavenExe -PathType Leaf) {
        Write-Host "[OK] Apache Maven $version already installed."
        exit 0
    }
    New-Item -ItemType Directory -Path $toolsDir -Force | Out-Null
    [Net.ServicePointManager]::SecurityProtocol = [Net.ServicePointManager]::SecurityProtocol -bor [Net.SecurityProtocolType]::Tls12
    if (-not (Test-Path -LiteralPath $archivePath -PathType Leaf)) {
        Invoke-WebRequest -Uri $downloadUrl -OutFile $archivePath -UseBasicParsing -TimeoutSec 180
    }
    $actual = (Get-FileHash -LiteralPath $archivePath -Algorithm SHA512).Hash.ToLowerInvariant()
    if ($actual -ne $checksum) {
        Remove-Item -LiteralPath $archivePath -Force -ErrorAction SilentlyContinue
        throw 'SHA-512 mismatch: Maven download failed verification.'
    }
    Expand-Archive -LiteralPath $archivePath -DestinationPath $toolsDir -Force
    if (-not (Test-Path -LiteralPath $mavenExe -PathType Leaf)) {
        throw 'The Maven executable is missing after extraction.'
    }
    Remove-Item -LiteralPath $archivePath -Force -ErrorAction SilentlyContinue
    Write-Host "[OK] Installed Apache Maven $version into .tools"
    exit 0
} catch {
    Write-Host "[ERROR] $($_.Exception.Message)" -ForegroundColor Red
    exit 1
}
