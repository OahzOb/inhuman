# Verify the assistants, the picker, and the usage breakdown on a device.
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

Write-Host '=== start clean ==='
& $adb -s $Serial shell am force-stop com.google.android.documentsui | Out-Null
& $adb -s $Serial shell pm clear com.inhuman | Out-Null
& $adb -s $Serial shell am start -n 'com.inhuman/.MainActivity' | Out-Null
Start-Sleep -Seconds 6

Write-Host ''
Write-Host '=== set the API key ==='
Tap-Find '^Settings$' 'Settings'
$field = Get-Nodes | Where-Object { $_.Cls -eq 'android.widget.EditText' } | Select-Object -First 1
if (-not $field) { Labels | ForEach-Object { Write-Host "  '$_'" }; throw 'no key field' }
Tap-Node $field 'key field'
$key = (Get-Content 'C:\android-toolchain\.deepseek-key' -Raw).Trim()
& $adb -s $Serial shell input text $key | Out-Null
Start-Sleep -Seconds 1
& $adb -s $Serial shell input keyevent KEYCODE_ESCAPE | Out-Null
Start-Sleep -Seconds 2
Tap-Find '^Save$' 'Save'
Start-Sleep -Seconds 2
Write-Host '  after saving:'
Labels | ForEach-Object { Write-Host "    '$_'" }
Save-Shot 'key'

Write-Host ''
Write-Host '=== the assistants screen ==='
Tap-Find '^‹$' 'back'
Tap-Find '^Assistants$' 'Assistants'
Start-Sleep -Seconds 2
Save-Shot 'agents'
Write-Host '  assistants screen:'
Labels | ForEach-Object { Write-Host "    '$_'" }

Write-Host ''
Write-Host '=== open the default assistant, which must show its system prompt ==='
$row = Get-Nodes | Where-Object { $_.Text -eq 'Assistant' } | Select-Object -First 1
if ($row) { Tap-Node $row 'the Assistant row' } else { Write-Host '  no Assistant row' }
Start-Sleep -Seconds 3
Save-Shot 'agent-editor'
Write-Host '  editor:'
Labels | ForEach-Object { Write-Host "    '$_'" }

$hasPrompt = (Get-Nodes | Where-Object { $_.Text -match 'workspace: a folder belonging' }) -ne $null
Write-Host ''
Write-Host "  >>> SYSTEM PROMPT VISIBLE IN THE EDITOR: $hasPrompt"

Write-Host ''
Write-Host '=== the model list, fetched from the provider ==='
$models = Get-Nodes | Where-Object { $_.Text -match 'deepseek' }
if ($models) {
    Write-Host '  models offered:'
    $models | ForEach-Object { Write-Host "    '$($_.Text)'" }
} else {
    Write-Host '  no models listed; editor shows:'
    Labels | ForEach-Object { Write-Host "    '$_'" }
}
