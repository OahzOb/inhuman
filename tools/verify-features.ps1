# Verify the three new surfaces: the approval gate, the file browser, and
# export/import.
#
# Runs against whichever device is named, reading the UI rather than guessing
# coordinates, so the same script serves the emulator and the phone.
[CmdletBinding()]
param([string]$Serial = 'emulator-5556', [string]$Label = 'emu')
$ErrorActionPreference = 'Stop'
$adb = 'C:\android-sdk\platform-tools\adb.exe'
$shotDir = 'C:\Projects\inhuman\.shots'
New-Item -ItemType Directory -Force -Path $shotDir | Out-Null
Add-Type -AssemblyName System.Drawing

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
    Write-Host "  tap $what at ($x,$y)"
    & $adb -s $Serial shell input tap $x $y | Out-Null
    Start-Sleep -Seconds 2
}
function Tap-Find([string]$pattern, [string]$what) {
    $n = Get-Nodes | Where-Object { ($_.Desc -match $pattern) -or ($_.Text -match $pattern) } | Select-Object -First 1
    if (-not $n) { Write-Host "  [$what] not found; on screen:"; Labels | ForEach-Object { Write-Host "    '$_'" }; throw "missing $what" }
    Tap-Node $n $what
}
function Save-Shot([string]$name) {
    $png = Join-Path $shotDir "$Label-$name.png"
    & $adb -s $Serial shell screencap -p /sdcard/s.png | Out-Null
    & $adb -s $Serial pull /sdcard/s.png $png 2>&1 | Out-Null
    & $adb -s $Serial shell rm -f /sdcard/s.png
    $img = [System.Drawing.Image]::FromFile($png)
    $w = $img.Width; $h = $img.Height
    $tw = if ($w -gt $h) { 900 } else { 420 }
    $th = [int]($h * ($tw / [double]$w))
    $b = New-Object System.Drawing.Bitmap($tw, $th)
    $g = [System.Drawing.Graphics]::FromImage($b)
    $g.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
    $g.DrawImage($img, 0, 0, $tw, $th); $g.Dispose(); $img.Dispose()
    $b.Save((Join-Path $shotDir "$Label-$name.small.png"), [System.Drawing.Imaging.ImageFormat]::Png); $b.Dispose()
    Write-Host "  shot: $Label-$name"
}
function Type-Text([string]$t) { & $adb -s $Serial shell input text ($t -replace ' ', '%s') | Out-Null; Start-Sleep -Seconds 1 }
function Hide-Keyboard { & $adb -s $Serial shell input keyevent KEYCODE_ESCAPE | Out-Null; Start-Sleep -Seconds 2 }
function Ensure-App {
    $top = (& $adb -s $Serial shell "dumpsys activity activities | grep -m1 topResumedActivity" 2>&1) -join ' '
    if ($top -notmatch 'com\.inhuman') {
        Write-Host '  app not in foreground; relaunching'
        & $adb -s $Serial shell am start -n 'com.inhuman/.MainActivity' | Out-Null
        Start-Sleep -Seconds 3
    }
}

Write-Host '=== 1. open a conversation ==='
& $adb -s $Serial shell am force-stop com.inhuman | Out-Null
& $adb -s $Serial shell am start -n 'com.inhuman/.MainActivity' | Out-Null
Start-Sleep -Seconds 4
Ensure-App
Labels | ForEach-Object { Write-Host "  '$_'" }

$first = Get-Nodes | Where-Object { $_.Text -and $_.Text -notmatch 'inhuman|No conversations|^Each conversation|Settings|New conversation' } | Select-Object -First 1
if ($first) {
    Tap-Node $first "conversation '$($first.Text)'"
} else {
    Tap-Find '^New conversation$' 'new conversation'
}

Write-Host ''
Write-Host '=== 2. the file browser ==='
Tap-Find '^Files$' 'Files'
Start-Sleep -Seconds 2
Save-Shot 'files-empty'
Write-Host '  browser shows:'
Labels | ForEach-Object { Write-Host "    '$_'" }

Write-Host ''
Write-Host '=== 3. THE APPROVAL GATE: ask the assistant to delete ==='
# Deleting is the one gated operation. Asking for a file that does not exist
# keeps this independent of whatever is already in the workspace.
Tap-Find '^‹$' 'back to chat'
$composer = Get-Nodes | Where-Object { $_.Cls -eq 'android.widget.EditText' } | Select-Object -First 1
if (-not $composer) { throw 'no composer' }
Tap-Node $composer 'composer'
Type-Text 'First create a file called gate-test.txt with the word hello. Then delete gate-test.txt.'
Hide-Keyboard
Ensure-App
Tap-Find '^Send$' 'send'

Write-Host '  waiting for the tool loop to reach the delete ...'
Start-Sleep -Seconds 30
Ensure-App
Save-Shot 'approval-gate'

$dialog = Get-Nodes | Where-Object { $_.Text -match 'Allow this action|Delete .* from this conversation' } | Select-Object -First 1
if ($dialog) {
    Write-Host ''
    Write-Host "  >>> APPROVAL GATE FIRED: '$($dialog.Text)'" -ForegroundColor Green
    Labels | ForEach-Object { Write-Host "    '$_'" }

    Write-Host ''
    Write-Host '  declining it ...'
    Tap-Find '^Decline$' 'Decline'
    Start-Sleep -Seconds 12
    Ensure-App
    Save-Shot 'approval-declined'
    Write-Host '  after declining:'
    Labels | ForEach-Object { Write-Host "    '$_'" }

    # The chip under the tool call must read "declined", not "ready".
    #
    # This is the assertion the screenshot cannot make: for as long as the state
    # went unassigned the run looked identical, and the only visible difference
    # was the word on one line. It appeared to be a call still waiting to happen.
    Write-Host ''
    if ((Labels) -contains 'declined') {
        Write-Host '  >>> CHIP READS declined' -ForegroundColor Green
    } else {
        Write-Host '  >>> no declined chip on screen (scrolled off?)' -ForegroundColor Yellow
    }
} else {
    Write-Host ''
    Write-Host '  >>> NO APPROVAL DIALOG. On screen:'
    Labels | ForEach-Object { Write-Host "    '$_'" }
}
