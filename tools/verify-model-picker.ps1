# Verify the model picker and the usage breakdown against the live API.
[CmdletBinding()]
param([string]$Serial = 'emulator-5556', [string]$Label = 'emu')
$ErrorActionPreference = 'Stop'
$adb = 'C:\android-sdk\platform-tools\adb.exe'
$shotDir = 'C:\Projects\inhuman\.shots'
New-Item -ItemType Directory -Force -Path $shotDir | Out-Null
Add-Type -AssemblyName System.Drawing

function Get-Nodes {
    & $adb -s $Serial shell uiautomator dump /sdcard/ui.xml 2>&1 | Out-Null
    $local = Join-Path $env:TEMP "u-$Serial.xml"
    & $adb -s $Serial pull /sdcard/ui.xml $local 2>&1 | Out-Null
    $xml = Get-Content $local -Raw
    $out = @()
    foreach ($tag in [regex]::Matches($xml, '<node\b[^>]*?(?:/>|>)')) {
        $t = $tag.Value
        $b = [regex]::Match($t, 'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
        if (-not $b.Success) { continue }
        $out += [pscustomobject]@{
            Desc = [regex]::Match($t, 'content-desc="([^"]*)"').Groups[1].Value
            Text = [regex]::Match($t, '\btext="([^"]*)"').Groups[1].Value
            Cls  = [regex]::Match($t, 'class="([^"]*)"').Groups[1].Value
            X1 = [int]$b.Groups[1].Value; Y1 = [int]$b.Groups[2].Value
            X2 = [int]$b.Groups[3].Value; Y2 = [int]$b.Groups[4].Value
        }
    }
    return $out
}
function Labels { (Get-Nodes | Where-Object { $_.Desc -or $_.Text } | ForEach-Object { if ($_.Desc) { $_.Desc } else { $_.Text } }) | Select-Object -Unique }
function Tap-Node([object]$n, [string]$what) {
    $x = [int](($n.X1 + $n.X2) / 2); $y = [int](($n.Y1 + $n.Y2) / 2)
    Write-Host "  tap $what ($x,$y)"
    & $adb -s $Serial shell input tap $x $y | Out-Null
    Start-Sleep -Seconds 2
}
function Tap-Find([string]$p, [string]$what) {
    $n = Get-Nodes | Where-Object { ($_.Desc -match $p) -or ($_.Text -match $p) } | Select-Object -First 1
    if (-not $n) { Write-Host "  [$what] missing; screen:"; Labels | ForEach-Object { Write-Host "    '$_'" }; throw "missing $what" }
    Tap-Node $n $what
}
function Save-Shot([string]$n) {
    $png = Join-Path $shotDir "$Label-$n.png"
    & $adb -s $Serial shell screencap -p /sdcard/s.png | Out-Null
    & $adb -s $Serial pull /sdcard/s.png $png 2>&1 | Out-Null
    $img = [System.Drawing.Image]::FromFile($png)
    $tw = if ($img.Width -gt $img.Height) { 900 } else { 420 }
    $b = New-Object System.Drawing.Bitmap($tw, [int]($img.Height * ($tw / [double]$img.Width)))
    $g = [System.Drawing.Graphics]::FromImage($b)
    $g.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
    $g.DrawImage($img, 0, 0, $b.Width, $b.Height); $g.Dispose(); $img.Dispose()
    $b.Save((Join-Path $shotDir "$Label-$n.small.png"), [System.Drawing.Imaging.ImageFormat]::Png); $b.Dispose()
    Write-Host "  shot: $Label-$n"
}
function Type-Text([string]$t) { & $adb -s $Serial shell input text ($t -replace ' ', '%s') | Out-Null; Start-Sleep -Seconds 1 }

Write-Host '=== key, then new conversation via the assistant picker ==='
& $adb -s $Serial shell am force-stop com.google.android.documentsui | Out-Null
& $adb -s $Serial shell pm clear com.inhuman | Out-Null
& $adb -s $Serial shell am start -n 'com.inhuman/.MainActivity' | Out-Null
Start-Sleep -Seconds 6
Tap-Find '^Settings$' 'Settings'
$field = Get-Nodes | Where-Object { $_.Cls -eq 'android.widget.EditText' } | Select-Object -First 1
Tap-Node $field 'key field'
& $adb -s $Serial shell input text ((Get-Content 'C:\android-toolchain\.deepseek-key' -Raw).Trim()) | Out-Null
Start-Sleep -Seconds 1
& $adb -s $Serial shell input keyevent KEYCODE_ESCAPE | Out-Null
Start-Sleep -Seconds 2
Tap-Find '^Save$' 'Save'
Start-Sleep -Seconds 1
Tap-Find '^‹$' 'back'

Write-Host ''
Write-Host '=== the assistant picker on a new conversation ==='
Tap-Find '^New conversation$' 'New conversation'
Start-Sleep -Seconds 3
Save-Shot 'agent-picker'
Write-Host '  picker shows:'
Labels | ForEach-Object { Write-Host "    '$_'" }

Write-Host ''
Write-Host '=== choose the default assistant ==='
Tap-Find '^Assistant$' 'the Assistant option'
Start-Sleep -Seconds 4

Write-Host ''
Write-Host '=== the model picker, from the title ==='
$title = Get-Nodes | Where-Object { $_.Text -match 'New conversation|default model' } | Select-Object -First 1
if ($title) { Tap-Node $title 'the title (opens the model picker)' } else { Write-Host '  no title node' }
Start-Sleep -Seconds 5
Save-Shot 'model-picker'
Write-Host '  model picker shows:'
Labels | ForEach-Object { Write-Host "    '$_'" }

Write-Host ''
Write-Host '=== turn thinking on, to expose the effort levels ==='
$thinking = Get-Nodes | Where-Object { $_.Cls -eq 'android.widget.Switch' } | Select-Object -First 1
if ($thinking) {
    Tap-Node $thinking 'the thinking switch'
    Start-Sleep -Seconds 3
    Write-Host '  after enabling thinking:'
    Labels | ForEach-Object { Write-Host "    '$_'" }
    Save-Shot 'effort-levels'
} else {
    Write-Host '  no switch found'
}
