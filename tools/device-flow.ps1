# Drive the app by reading the UI, not by guessing coordinates.
#
# Every action here is confirmed by re-reading the screen before the next one.
# A tap that lands on empty space raises no error -- it just produces a
# screenshot that looks unchanged and a conclusion that is wrong.
#
# Written to work on any device: nothing is hardcoded to a resolution, and the
# screen size is read at start.
[CmdletBinding()]
param(
    [string]$Serial,
    [string]$Label = 'phone',
    [string]$ApiKeyFile = 'C:\android-toolchain\.deepseek-key'
)

$ErrorActionPreference = 'Stop'
$adb = 'C:\android-sdk\platform-tools\adb.exe'
$repo = Split-Path $PSScriptRoot -Parent
$shotDir = Join-Path $repo '.shots'
New-Item -ItemType Directory -Force -Path $shotDir | Out-Null
Add-Type -AssemblyName System.Drawing

if (-not $Serial) {
    $Serial = (& $adb devices 2>&1 |
        Select-String -Pattern '^\S+\s+device' |
        ForEach-Object { ($_ -split '\s+')[0] } |
        Where-Object { $_ -notlike 'emulator-*' } |
        Select-Object -First 1)
    if (-not $Serial) { throw 'no physical device attached' }
}

function Get-Nodes {
    & $adb -s $Serial shell uiautomator dump /sdcard/ui.xml 2>&1 | Out-Null
    $local = Join-Path $env:TEMP "ui-$Serial.xml"
    & $adb -s $Serial pull /sdcard/ui.xml $local 2>&1 | Out-Null
    $xml = Get-Content $local -Raw
    $out = @()
    foreach ($tag in [regex]::Matches($xml, '<node\b[^>]*?(?:/>|>)')) {
        $t = $tag.Value
        $b = [regex]::Match($t, 'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
        if (-not $b.Success) { continue }
        $out += [pscustomobject]@{
            Desc      = [regex]::Match($t, 'content-desc="([^"]*)"').Groups[1].Value
            Text      = [regex]::Match($t, '\btext="([^"]*)"').Groups[1].Value
            Cls       = [regex]::Match($t, 'class="([^"]*)"').Groups[1].Value
            Checkable = [regex]::Match($t, 'checkable="([^"]*)"').Groups[1].Value -eq 'true'
            Checked   = [regex]::Match($t, 'checked="([^"]*)"').Groups[1].Value -eq 'true'
            X1 = [int]$b.Groups[1].Value; Y1 = [int]$b.Groups[2].Value
            X2 = [int]$b.Groups[3].Value; Y2 = [int]$b.Groups[4].Value
        }
    }
    return $out
}

function Show-Labels([string]$when) {
    Write-Host "  [$when] on screen:"
    (Get-Nodes | Where-Object { $_.Desc -or $_.Text } |
        ForEach-Object { if ($_.Desc) { $_.Desc } else { $_.Text } } |
        Select-Object -Unique) | ForEach-Object { Write-Host "    '$_'" }
}

function Tap-Node([object]$n, [string]$what) {
    $x = [int](($n.X1 + $n.X2) / 2); $y = [int](($n.Y1 + $n.Y2) / 2)
    Write-Host "  tap $what at ($x,$y)"
    & $adb -s $Serial shell input tap $x $y | Out-Null
    Start-Sleep -Seconds 2
}

function Find-Node([string]$pattern, [switch]$Desc) {
    Get-Nodes | Where-Object {
        if ($Desc) { $_.Desc -match $pattern } else { ($_.Desc -match $pattern) -or ($_.Text -match $pattern) }
    } | Select-Object -First 1
}

function Tap-Find([string]$pattern, [string]$what, [switch]$Desc) {
    $n = Find-Node $pattern -Desc:$Desc
    if (-not $n) { Show-Labels "looking for $what"; throw "could not find $what (pattern $pattern)" }
    Tap-Node $n $what
}

function In-App {
    $top = (& $adb -s $Serial shell "dumpsys activity activities | grep -m1 topResumedActivity" 2>&1) -join ' '
    return $top -match 'com\.inhuman'
}

function Ensure-App {
    if (-not (In-App)) {
        Write-Host '  app not in foreground; relaunching'
        & $adb -s $Serial shell am start -n 'com.inhuman/.MainActivity' | Out-Null
        Start-Sleep -Seconds 3
    }
}

function Save-Shot([string]$name) {
    $png = Join-Path $shotDir "$Label-$name.png"
    & $adb -s $Serial shell screencap -p /sdcard/s.png | Out-Null
    & $adb -s $Serial pull /sdcard/s.png $png 2>&1 | Out-Null
    & $adb -s $Serial shell rm -f /sdcard/s.png
    $img = [System.Drawing.Image]::FromFile($png)
    $w = $img.Width; $h = $img.Height
    $tw = 420; $th = [int]($h * ($tw / [double]$w))
    $b = New-Object System.Drawing.Bitmap($tw, $th)
    $g = [System.Drawing.Graphics]::FromImage($b)
    $g.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
    $g.DrawImage($img, 0, 0, $tw, $th); $g.Dispose(); $img.Dispose()
    $b.Save((Join-Path $shotDir "$Label-$name.small.png"), [System.Drawing.Imaging.ImageFormat]::Png); $b.Dispose()
    Write-Host "  shot: $Label-$name  (${w}x${h})"
}

function Type-Text([string]$text) {
    # Passed through three layers of quoting, so anything exotic gets mangled.
    & $adb -s $Serial shell input text ($text -replace ' ', '%s') | Out-Null
    Start-Sleep -Seconds 1
}

function Hide-Keyboard {
    # Escape rather than Back: with the keyboard already dismissed, Back
    # finishes the activity and drops to the launcher, which silently ends the
    # run at whatever screen it lands on.
    & $adb -s $Serial shell input keyevent KEYCODE_ESCAPE | Out-Null
    Start-Sleep -Seconds 2
}

# --- run ------------------------------------------------------------------

$model = ((& $adb -s $Serial shell getprop ro.product.model 2>&1) -join '').Trim()
$size = ((& $adb -s $Serial shell wm size 2>&1) -join '').Trim()
$density = ((& $adb -s $Serial shell wm density 2>&1) -join '').Trim()
Write-Host "device : $Serial  ($model)"
Write-Host "display: $size  $density"
Write-Host ''

Write-Host '=== 1. fresh start ==='
& $adb -s $Serial shell pm clear com.inhuman | Out-Null
& $adb -s $Serial shell am start -n 'com.inhuman/.MainActivity' | Out-Null
Start-Sleep -Seconds 5
Ensure-App

Write-Host ''
Write-Host '=== 2. enter the API key ==='
Tap-Find '^Settings$' 'settings' -Desc
$field = Get-Nodes | Where-Object { $_.Cls -eq 'android.widget.EditText' } | Select-Object -First 1
if (-not $field) { Show-Labels 'settings'; throw 'no key field' }
Tap-Node $field 'key field'
$key = (Get-Content $ApiKeyFile -Raw).Trim()
& $adb -s $Serial shell input text $key | Out-Null
Start-Sleep -Seconds 1
Hide-Keyboard
Tap-Find '^Save$' 'Save'
Start-Sleep -Seconds 1
Tap-Find '^Test connection$' 'Test connection'
Start-Sleep -Seconds 8
$status = Find-Node 'Connected|failed|invalid|balance|HTTP'
if ($status) { Write-Host "  status: $($status.Text)" } else { Write-Host '  no status text' }
Save-Shot 'key-saved'

Write-Host ''
Write-Host '=== 3. new conversation, ask the agent to create a file ==='
& $adb -s $Serial shell am force-stop com.inhuman | Out-Null
Start-Sleep -Seconds 1
& $adb -s $Serial shell am start -n 'com.inhuman/.MainActivity' | Out-Null
Start-Sleep -Seconds 4
Ensure-App
Tap-Find '^New conversation$' 'new conversation' -Desc

$composer = Get-Nodes | Where-Object { $_.Cls -eq 'android.widget.EditText' } | Select-Object -First 1
Tap-Node $composer 'composer'
Type-Text 'Create a file called notes.txt containing the line: item one. Then say done.'
Hide-Keyboard
Ensure-App
Tap-Find '^Send$' 'send' -Desc

Write-Host '  waiting for the tool loop ...'
Start-Sleep -Seconds 22
Save-Shot 'agent-created'
$after = (Get-Nodes | Where-Object { $_.Text } | Select-Object -ExpandProperty Text)
Write-Host '  transcript:'
$after | ForEach-Object { Write-Host "    '$_'" }

Write-Host ''
Write-Host '=== 4. THE APPROVAL GATE: ask it to delete ==='
$composer = Get-Nodes | Where-Object { $_.Cls -eq 'android.widget.EditText' } | Select-Object -First 1
Tap-Node $composer 'composer'
Type-Text 'Delete the file notes.txt. Then say done.'
Hide-Keyboard
Ensure-App
Tap-Find '^Send$' 'send' -Desc

Write-Host '  waiting for the delete call ...'
Start-Sleep -Seconds 18
Ensure-App
Save-Shot 'approval-dialog'

$dialog = Find-Node 'Allow this action|Delete'
if ($dialog) {
    Write-Host "  >>> APPROVAL DIALOG APPEARED: '$($dialog.Text)'"
    Show-Labels 'with the dialog up'
} else {
    Write-Host '  >>> no approval dialog; transcript:'
    (Get-Nodes | Where-Object { $_.Text } | Select-Object -ExpandProperty Text) |
        ForEach-Object { Write-Host "    '$_'" }
}
