#Requires -Version 5.1
<#
.SYNOPSIS
    Build, install to a device, launch, and capture a screenshot.

.DESCRIPTION
    One command for the whole loop, with the device chosen explicitly. Picking
    the device matters once more than one is attached: installing onto the
    emulator when the phone was meant would look like success and test the wrong
    screen size entirely.

.PARAMETER Serial
    adb serial to target. Defaults to the only attached device, or the emulator
    when several are attached.

.PARAMETER Label
    Screenshot name. Defaults to a timestamped name.

.PARAMETER SkipBuild
    Install and capture the APK already on disk. Useful when only the capture
    needs redoing.

.EXAMPLE
    .\tools\build-and-shot.ps1
    .\tools\build-and-shot.ps1 -Serial e4897f1f -Label phone-empty
#>
[CmdletBinding()]
param(
    [string]$Serial,
    [string]$Label,
    [switch]$SkipBuild
)

$ErrorActionPreference = 'Stop'

$env:JAVA_HOME = 'C:\android-toolchain\jdk17'
$adb = 'C:\android-sdk\platform-tools\adb.exe'
$repo = Split-Path $PSScriptRoot -Parent

function Get-AttachedDevices {
    & $adb devices 2>&1 |
        Select-Object -Skip 1 |
        Where-Object { $_ -match '\sdevice\s*$' } |
        ForEach-Object { ($_ -split '\s+')[0] }
}

if (-not $Serial) {
    $attached = @(Get-AttachedDevices)
    if ($attached.Count -eq 0) { throw 'no device attached' }
    # Prefer the emulator only when there is a choice; a single attached device
    # is never ambiguous.
    $Serial = if ($attached.Count -eq 1) { $attached[0] }
              else { ($attached | Where-Object { $_ -like 'emulator-*' } | Select-Object -First 1) ?? $attached[0] }
}
Write-Host "device : $Serial"

# Wait for the device to be usable, not merely listed -- a phone that was just
# plugged in can appear before its package manager is ready.
Write-Host 'waiting  : device ready'
$deadline = (Get-Date).AddMinutes(3)
while ((Get-Date) -lt $deadline) {
    $booted = ((& $adb -s $Serial shell getprop sys.boot_completed 2>&1) -join '').Trim()
    if ($booted -eq '1') { break }
    Start-Sleep -Seconds 3
}

$model = ((& $adb -s $Serial shell getprop ro.product.model 2>&1) -join '').Trim()
$api = ((& $adb -s $Serial shell getprop ro.build.version.sdk 2>&1) -join '').Trim()
$size = ((& $adb -s $Serial shell wm size 2>&1) -join '').Trim()
$density = ((& $adb -s $Serial shell wm density 2>&1) -join '').Trim()
Write-Host "model    : $model  (API $api)"
Write-Host "display  : $size  $density"

if (-not $SkipBuild) {
    Write-Host ''
    Write-Host '=== building ==='
    Push-Location $repo
    try {
        & '.\gradlew.bat' :app:assembleDebug --console=plain 2>&1 |
            Select-String -Pattern 'BUILD SUCCESSFUL|BUILD FAILED|^e: ' |
            ForEach-Object { Write-Host "  $($_.Line.Trim())" }
        if ($LASTEXITCODE -ne 0) { throw 'build failed' }
    } finally { Pop-Location }
}

$apk = Join-Path $repo 'app\build\outputs\apk\debug\app-debug.apk'
Write-Host ''
Write-Host '=== installing ==='
& $adb -s $Serial install -r $apk 2>&1 | ForEach-Object { Write-Host "  $_" }

Write-Host ''
Write-Host '=== launching ==='
& $adb -s $Serial shell am start -W -n 'com.inhuman/.MainActivity' 2>&1 |
    Select-Object -First 3 | ForEach-Object { Write-Host "  $_" }
Start-Sleep -Seconds 3

if (-not $Label) { $Label = 'shot-{0:HHmmss}' -f (Get-Date) }

Write-Host ''
Write-Host '=== capturing ==='
$shotDir = Join-Path $repo '.shots'
New-Item -ItemType Directory -Force -Path $shotDir | Out-Null
$png = Join-Path $shotDir "$Label.png"
& $adb -s $Serial shell screencap -p /sdcard/shot.png
& $adb -s $Serial pull /sdcard/shot.png $png | Out-Null
& $adb -s $Serial shell rm -f /sdcard/shot.png

Add-Type -AssemblyName System.Drawing
$img = [System.Drawing.Image]::FromFile($png)
$w = $img.Width; $h = $img.Height
$bmp = New-Object System.Drawing.Bitmap($img)

# A black framebuffer is the failure that wastes time, because screencap
# reports success either way. Sample a grid and report the mean.
$sum = 0.0; $n = 0
$stepX = [Math]::Max(1, [int]($w / 60)); $stepY = [Math]::Max(1, [int]($h / 60))
for ($y = 0; $y -lt $h; $y += $stepY) {
    for ($x = 0; $x -lt $w; $x += $stepX) {
        $p = $bmp.GetPixel($x, $y)
        $sum += (0.299 * $p.R + 0.587 * $p.G + 0.114 * $p.B); $n++
    }
}
$bmp.Dispose(); $img.Dispose()
$mean = if ($n) { $sum / $n } else { 0 }

# Scaled copy, so a phone-shaped screenshot is still viewable in one glance.
$small = Join-Path $shotDir "$Label.small.png"
$targetW = if ($w -gt $h) { 900 } else { 420 }
$src = [System.Drawing.Image]::FromFile($png)
$nh = [int]($h * ($targetW / [double]$w))
$b2 = New-Object System.Drawing.Bitmap($targetW, $nh)
$g = [System.Drawing.Graphics]::FromImage($b2)
$g.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
$g.DrawImage($src, 0, 0, $targetW, $nh); $g.Dispose(); $src.Dispose()
$b2.Save($small, [System.Drawing.Imaging.ImageFormat]::Png); $b2.Dispose()

Write-Host ("  shot    : {0}" -f $png)
Write-Host ("  size    : {0}x{1}" -f $w, $h)
Write-Host ("  mean    : {0:N1}/255 brightness" -f $mean)
if ($mean -lt 2) { Write-Host '  WARNING : frame is essentially black' -ForegroundColor Yellow }
Write-Host ("  preview : {0}" -f $small)
