# Wait for the emulator, build, install and capture a screenshot.
#
# The project deliberately keeps its tooling in one script rather than relying
# on the shell's PATH: the toolchain lives outside the project on purpose, and a
# script that names it explicitly is reproducible from any shell.

$ErrorActionPreference = 'Stop'

$env:JAVA_HOME = 'C:\android-toolchain\jdk17'
$adb = 'C:\android-sdk\platform-tools\adb.exe'
$serial = 'emulator-5554'

Write-Host '=== waiting for the emulator to boot ==='
$deadline = (Get-Date).AddMinutes(5)
while ((Get-Date) -lt $deadline) {
    $devices = & $adb devices 2>&1
    if ($devices -match [regex]::Escape($serial)) { break }
    Start-Sleep -Seconds 3
}
if (-not ((& $adb devices 2>&1) -match [regex]::Escape($serial))) {
    throw "emulator $serial never appeared"
}

$deadline = (Get-Date).AddMinutes(6)
while ((Get-Date) -lt $deadline) {
    $booted = ((& $adb -s $serial shell getprop sys.boot_completed 2>&1) -join '').Trim()
    if ($booted -eq '1') { break }
    Write-Host '  still booting ...'
    Start-Sleep -Seconds 5
}
Write-Host ("  boot_completed = '{0}'" -f (((& $adb -s $serial shell getprop sys.boot_completed 2>&1) -join '').Trim()))

Write-Host ''
Write-Host '=== building ==='
Push-Location 'C:\Projects\inhuman'
try {
    & '.\gradlew.bat' :app:assembleDebug --console=plain 2>&1 |
        Select-String -Pattern 'BUILD SUCCESSFUL|BUILD FAILED|^e: ' | ForEach-Object { Write-Host "  $($_.Line)" }
    if ($LASTEXITCODE -ne 0) { throw 'build failed' }
} finally { Pop-Location }

$apk = 'C:\Projects\inhuman\app\build\outputs\apk\debug\app-debug.apk'
Write-Host ''
Write-Host '=== installing ==='
& $adb -s $serial install -r $apk 2>&1 | ForEach-Object { Write-Host "  $_" }

Write-Host ''
Write-Host '=== launching ==='
& $adb -s $serial shell am start -W -n 'com.inhuman/.MainActivity' 2>&1 | Select-Object -First 4 | ForEach-Object { Write-Host "  $_" }
Start-Sleep -Seconds 3

Write-Host ''
Write-Host '=== capturing ==='
$shotDir = 'C:\Projects\inhuman\.shots'
New-Item -ItemType Directory -Force -Path $shotDir | Out-Null
$png = Join-Path $shotDir 'phase0-empty.png'
& $adb -s $serial shell screencap -p /sdcard/phase0.png
& $adb -s $serial pull /sdcard/phase0.png $png | Out-Null
& $adb -s $serial shell rm -f /sdcard/phase0.png

Add-Type -AssemblyName System.Drawing
$img = [System.Drawing.Image]::FromFile($png)
$w = $img.Width; $h = $img.Height
$bmp = New-Object System.Drawing.Bitmap($img)
$img.Dispose()

# Mean brightness, so an all-black frame cannot pass silently -- the failure
# mode that wasted time before.
$sum = 0.0; $n = 0
for ($y = 0; $y -lt $h; $y += [Math]::Max(1, [int]($h / 50))) {
    for ($x = 0; $x -lt $w; $x += [Math]::Max(1, [int]($w / 50))) {
        $p = $bmp.GetPixel($x, $y)
        $sum += (0.299 * $p.R + 0.587 * $p.G + 0.114 * $p.B); $n++
    }
}
$bmp.Dispose()
$mean = if ($n) { $sum / $n } else { 0 }

# Scaled copy for review.
$small = Join-Path $shotDir 'phase0-empty.small.png'
$src = [System.Drawing.Image]::FromFile($png)
$nw = 900; $nh = [int]($h * (900.0 / $w))
$b2 = New-Object System.Drawing.Bitmap($nw, $nh)
$g = [System.Drawing.Graphics]::FromImage($b2)
$g.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
$g.DrawImage($src, 0, 0, $nw, $nh); $g.Dispose(); $src.Dispose()
$b2.Save($small, [System.Drawing.Imaging.ImageFormat]::Png); $b2.Dispose()

Write-Host ("  shot   : {0}" -f $png)
Write-Host ("  size   : {0}x{1}" -f $w, $h)
Write-Host ("  mean   : {0:N1}/255 brightness" -f $mean)
if ($mean -lt 2) { Write-Host '  WARNING: frame is essentially black' -ForegroundColor Yellow }
Write-Host ("  preview: {0}" -f $small)
