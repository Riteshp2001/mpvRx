<#
.SYNOPSIS
  Downloads libmpv (shinchiro/mpv-winbuild-cmake) into desktopApp/native/windows-<arch>
  so `gradlew :desktopApp:run` and the MSI/EXE packages ship the player engine.
.PARAMETER Arch
  x64 (default) or arm64.
.PARAMETER WithYtDlp
  Also download yt-dlp.exe next to libmpv for streaming site support.
#>
param(
  [ValidateSet('x64', 'arm64')]
  [string]$Arch = 'x64',
  [switch]$WithYtDlp
)

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'

$root = Split-Path -Parent $PSScriptRoot
$target = Join-Path $root "desktopApp\native\windows-$Arch"
New-Item -ItemType Directory -Force $target | Out-Null

$pattern = if ($Arch -eq 'x64') { '^mpv-dev-x86_64-\d{8}-git-[0-9a-f]+\.7z$' } else { '^mpv-dev-aarch64-\d{8}-git-[0-9a-f]+\.7z$' }
Write-Host 'Resolving latest libmpv build...'
$release = Invoke-RestMethod 'https://api.github.com/repos/shinchiro/mpv-winbuild-cmake/releases/latest' -Headers @{ 'User-Agent' = 'mpvRx-build' }
$asset = $release.assets | Where-Object { $_.name -match $pattern } | Select-Object -First 1
if (-not $asset) { throw "No libmpv asset matching $pattern in release $($release.tag_name)" }

$work = Join-Path ([IO.Path]::GetTempPath()) ("mpvrx-libmpv-" + [Guid]::NewGuid())
New-Item -ItemType Directory -Force $work | Out-Null
try {
  $archive = Join-Path $work $asset.name
  Write-Host "Downloading $($asset.name)..."
  Invoke-WebRequest $asset.browser_download_url -OutFile $archive -UseBasicParsing

  $extract = Join-Path $work 'x'
  New-Item -ItemType Directory -Force $extract | Out-Null
  $sevenZip = Get-Command 7z.exe -ErrorAction SilentlyContinue
  if ($sevenZip) {
    & $sevenZip.Source x $archive "-o$extract" -y | Out-Null
  } else {
    # Windows 10 1803+ ships bsdtar, which reads 7z archives.
    & tar.exe -xf $archive -C $extract
  }
  if ($LASTEXITCODE -ne 0) { throw 'Extraction failed; install 7-Zip and retry.' }

  $dll = Get-ChildItem $extract -Recurse -Filter 'libmpv-2.dll' | Select-Object -First 1
  if (-not $dll) { throw 'libmpv-2.dll not found in archive' }
  Copy-Item $dll.FullName (Join-Path $target 'libmpv-2.dll') -Force
  Write-Host "libmpv-2.dll -> $target"

  if ($WithYtDlp) {
    $ytdlp = if ($Arch -eq 'x64') { 'yt-dlp.exe' } else { 'yt-dlp_arm64.exe' }
    Invoke-WebRequest "https://github.com/yt-dlp/yt-dlp/releases/latest/download/$ytdlp" -OutFile (Join-Path $target 'yt-dlp.exe') -UseBasicParsing
    Write-Host "yt-dlp.exe -> $target"
  }
} finally {
  Remove-Item $work -Recurse -Force -ErrorAction SilentlyContinue
}
