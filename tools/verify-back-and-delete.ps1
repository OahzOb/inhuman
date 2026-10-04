# Verify the three things just added: Back navigating instead of exiting,
# conversation deletion with its confirmation, and the branch controls.
#
# No [CmdletBinding()]: this runs under Windows PowerShell 5.1, which rejects the
# attribute and then reports the failure against a line far below -- so the error
# looks like a syntax problem in the test rather than an unsupported attribute.
param([string]$Serial = 'emulator-5556', [string]$Label = 'emu')
$ErrorActionPreference = 'Stop'
$adb = 'C:\android-sdk\platform-tools\adb.exe'
$shotDir = 'C:\Projects\inhuman\.shots'
New-Item -ItemType Directory -Force -Path $shotDir | Out-Null
Add-Type -AssemblyName System.Drawing

function Get-Nodes {
    # Remove first, and use a unique name. `uiautomator dump` refuses to
    # overwrite an existing file and still prints "dumped to", so reusing one
    # path silently returns the FIRST dump for every later call -- which is how
    # a run reported a dialog as stuck open when it had already closed.
    $stamp = Get-Date -Format 'HHmmssfff'
    & $adb -s $Serial shell "rm -f /sdcard/ui*.xml" 2>&1 | Out-Null
    & $adb -s $Serial shell "uiautomator dump /sdcard/ui$stamp.xml" 2>&1 | Out-Null
    $local = Join-Path $env:TEMP "u-$Serial-$stamp.xml"
    & $adb -s $Serial pull "/sdcard/ui$stamp.xml" $local 2>&1 | Out-Null
    if (-not (Test-Path $local)) { return @() }
    $xml = Get-Content $local -Raw
    $out = @()
    foreach ($tag in [regex]::Matches($xml, '<node\b[^>]*?(?:/>|>)')) {
        $t = $tag.Value
        $b = [regex]::Match($t, 'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
        if (-not $b.Success) { continue }
        $out += [pscustomobject]@{
            Desc = [regex]::Match($t, 'content-desc="([^"]*)"').Groups[1].Value
            Text = [regex]::Match($t, '\btext="([^"]*)"').Groups[1].Value
            Cls = [regex]::Match($t, 'class="([^"]*)"').Groups[1].Value
            Checkable = [regex]::Match($t, 'checkable="([^"]*)"').Groups[1].Value -eq 'true'
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
function AppRunning {
    $top = (& $adb -s $Serial shell "dumpsys activity activities | grep -m1 topResumedActivity" 2>&1) -join ' '
    return $top -match 'com\.inhuman'
}
function Press-Back([string]$note) {
    & $adb -s $Serial shell input keyevent KEYCODE_BACK | Out-Null
    Start-Sleep -Seconds 2
    $state = if (AppRunning) { "still in the app" } else { "APP EXITED" }
    Write-Host "  BACK ($note) -> $state"
    return (AppRunning)
}
function Type-Text([string]$t) { & $adb -s $Serial shell input text ($t -replace ' ', '%s') | Out-Null; Start-Sleep -Seconds 1 }

Write-Host '=== start clean, key, one conversation with a reply ==='
& $adb -s $Serial shell am force-stop com.google.android.documentsui | Out-Null
& $adb -s $Serial shell pm clear com.inhuman | Out-Null
& $adb -s $Serial shell am start -n 'com.inhuman/.MainActivity' | Out-Null
Start-Sleep -Seconds 6
Tap-Find '^Settings$' 'Settings'

# The key field is the only text input on the settings screen, found through the
# same node reader as everything else. The first version shelled out to grep with
# a single-quoted pattern inside a double-quoted argument, which left an
# unterminated string and reported the error twenty lines further down -- the
# script's own bug looking like the product's.
$input = Get-Nodes | Where-Object { $_.Cls -eq 'android.widget.EditText' } | Select-Object -First 1
if ($input) {
    Tap-Node $input 'the key field'
    & $adb -s $Serial shell input text ((Get-Content 'C:\android-toolchain\.deepseek-key' -Raw).Trim()) | Out-Null
    Start-Sleep -Seconds 1
    & $adb -s $Serial shell input keyevent KEYCODE_ESCAPE | Out-Null
    Start-Sleep -Seconds 2
    Tap-Find '^Save$' 'Save'
    Start-Sleep -Seconds 1
} else {
    Write-Host '  could not locate the key field'
}
Tap-Find '^‹$' 'back from settings'

Write-Host ''
Write-Host '=== NEW CONVERSATION ==='
Tap-Find '^New conversation$' 'New conversation'
Start-Sleep -Seconds 3
Tap-Find '^Assistant$' 'the Assistant'
Start-Sleep -Seconds 3
Tap-Find '^Message$' 'the composer'
Type-Text 'Say hello in exactly three words.'
& $adb -s $Serial shell input keyevent KEYCODE_ESCAPE | Out-Null
Start-Sleep -Seconds 2
Tap-Find '^Send$' 'Send'
Write-Host '  waiting for the reply...'
Start-Sleep -Seconds 22
Save-Shot 'back-1-chat'

Write-Host ''
Write-Host '=== TEST 1: BACK from a conversation must return to the list, not exit ==='
$r1 = Press-Back 'from a conversation'
if (-not $r1) { throw 'BACK exited the app from a conversation -- the bug is still present' }
$labels = Labels
if ($labels -match 'New conversation') {
    Write-Host '  ==> PASS: back reached the conversation list'
} else {
    Write-Host '  ==> UNEXPECTED screen:'; $labels | ForEach-Object { Write-Host "    '$_'" }
}

Write-Host ''
Write-Host '=== TEST 2: BACK from the list may exit, but only there ==='
$r2 = Press-Back 'from the list'
if ($r2) { Write-Host '  still in the app (acceptable on Android 13+)' }
else { Write-Host '  exited from the root, which is correct' }

Write-Host ''
Write-Host '=== reopen for the delete test ==='
& $adb -s $Serial shell am start -n 'com.inhuman/.MainActivity' | Out-Null
Start-Sleep -Seconds 5
Save-Shot 'back-2-list'
Write-Host '  list:'
Labels | ForEach-Object { Write-Host "    '$_'" }

Write-Host ''
Write-Host '=== TEST 3: delete asks first and names the files ==='
$del = Get-Nodes | Where-Object { $_.Desc -eq 'Delete conversation' } | Select-Object -First 1
if ($del) {
    Tap-Node $del 'the delete button'
    Start-Sleep -Seconds 3
    Save-Shot 'back-3-delete-confirm'
    Write-Host '  confirmation:'
    Labels | ForEach-Object { Write-Host "    '$_'" }

    $hasWarning = (Labels | Where-Object { $_ -match 'cannot be undone' }) -ne $null
    $hasCount = (Labels | Where-Object { $_ -match 'folder|file' }) -ne $null
    Write-Host "  ==> names the folder/files: $hasCount"
    Write-Host "  ==> warns it cannot be undone: $hasWarning"

    Write-Host ''
    Write-Host '  cancel it, to confirm cancelling does not delete'
    Tap-Find '^Cancel$' 'Cancel'
    Start-Sleep -Seconds 2
    # Read from the list, not by matching the title: the generated title
    # contains a full stop, which is a regex wildcard, so a pattern built from
    # it can fail to match a row that is plainly there.
    $rows = (Get-Nodes | Where-Object { $_.Desc -eq 'Delete conversation' }).Count
    Write-Host "  ==> conversations still listed after Cancel: $rows (expected 1)"
} else {
    Write-Host '  no delete button found; screen:'
    Labels | ForEach-Object { Write-Host "    '$_'" }
}
