<#
Build a signed release APK, verify its signature, and write the static update
manifest served by tools/dev-server.py.

The manifest deliberately uses a relative APK URL: the app resolves it against
whatever base URL the user configured, so moving the server (LAN IP, Tailscale
name, object storage) does not require editing update.json.

This does not bump versionCode. Bump app/build.gradle before running it, or the
manifest will describe a build Android will refuse to install over the current
one.
#>
param(
	[string]$Notes = "",
	[switch]$SkipBuild,
	[string]$Dist = "dist"
)

$ErrorActionPreference = "Stop"
$repo = Split-Path -Parent $PSScriptRoot
Set-Location $repo
if (-not $env:JAVA_HOME) {
	$env:JAVA_HOME = "C:\android-toolchain\jdk17"
}

if (-not $SkipBuild) {
	& .\gradlew.bat :app:assembleRelease --console=plain
	if ($LASTEXITCODE -ne 0) {
		throw "assembleRelease failed with exit code $LASTEXITCODE"
	}
}

$apk = Join-Path $repo "app\build\outputs\apk\release\app-release.apk"
if (-not (Test-Path $apk)) {
	throw "release APK not found: $apk"
}

$keystore = Join-Path $repo "keystore.properties"
if (-not (Test-Path $keystore)) {
	Write-Warning "keystore.properties is absent: this APK is debug-signed and cannot update a release-signed install."
}

$sdk = $env:ANDROID_HOME
if (-not $sdk) { $sdk = "C:\android-sdk" }
$buildTools = Get-ChildItem (Join-Path $sdk "build-tools") -Directory -ErrorAction SilentlyContinue |
	Sort-Object Name -Descending
$aapt2 = $buildTools | ForEach-Object { Join-Path $_.FullName "aapt2.exe" } |
	Where-Object { Test-Path $_ } | Select-Object -First 1
$apksigner = $buildTools | ForEach-Object { Join-Path $_.FullName "apksigner.bat" } |
	Where-Object { Test-Path $_ } | Select-Object -First 1

if ($apksigner) {
	& $apksigner verify --print-certs $apk | ForEach-Object { Write-Host "  $_" }
	if ($LASTEXITCODE -ne 0) {
		throw "apksigner rejected $apk"
	}
} else {
	Write-Warning "apksigner not found; the APK signature was not verified."
}

$versionCode = $null
$versionName = $null
if ($aapt2) {
	$badging = & $aapt2 dump badging $apk 2>&1
	$packageLine = $badging | Where-Object {
		$_ -match "^package: name='([^']+)' versionCode='([^']+)' versionName='([^']+)'"
	} | Select-Object -First 1
	if ($packageLine -and $packageLine -match "versionCode='([^']+)' versionName='([^']+)'") {
		$versionCode = $Matches[1]
		$versionName = $Matches[2]
	}
}

if (-not $versionCode -or -not $versionName) {
	$gradle = Get-Content (Join-Path $repo "app\build.gradle") -Raw
	$versionCode = [regex]::Match($gradle, "versionCode\s*=\s*(\d+)").Groups[1].Value
	$versionName = [regex]::Match($gradle, "versionName\s*=\s*'([^']+)'").Groups[1].Value
}
if (-not $versionCode -or -not $versionName) {
	throw "could not read versionCode/versionName"
}

$distPath = Join-Path $repo $Dist
New-Item -ItemType Directory -Force -Path $distPath | Out-Null
$name = "inhuman-$versionName-$versionCode.apk"
Copy-Item $apk (Join-Path $distPath $name) -Force
$sha = (Get-FileHash (Join-Path $distPath $name) -Algorithm SHA256).Hash.ToLowerInvariant()

$manifest = [ordered]@{
	versionCode = [long]$versionCode
	versionName = $versionName
	url = $name
	sha256 = $sha
	notes = $Notes
}
$json = $manifest | ConvertTo-Json
# Explicit UTF-8 without a BOM: the app parses this as JSON, and Windows
# PowerShell's `Set-Content -Encoding utf8` writes a BOM.
$utf8NoBom = New-Object System.Text.UTF8Encoding($false)
[System.IO.File]::WriteAllText(
	(Join-Path $distPath "update.json"),
	$json,
	$utf8NoBom
)

Write-Host ""
Write-Host "APK      $distPath\$name"
Write-Host "SHA-256  $sha"
Write-Host "version  $versionName ($versionCode)"
Write-Host "manifest $distPath\update.json"
Write-Host ""
Write-Host "Serve it with:"
Write-Host "  python tools\dev-server.py"
