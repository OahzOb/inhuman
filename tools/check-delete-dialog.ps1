# Does the delete confirmation survive its own subject?
#
# The report: with the confirmation open, a tap outside it does something, and
# afterwards the description names a different conversation.
#
# Reading the state properly matters here. An earlier attempt parsed
# `text="..."` and the title -- which embeds a quoted conversation name --
# comes back single-quoted by uiautomator, so it read as empty and three runs
# in a row reported "no change" from a value that was never read.
param([string]$Serial = 'emulator-5554')
$ErrorActionPreference = 'Continue'
Import-Module "$PSScriptRoot\UiDump.psm1" -Force
$adb = 'C:\android-sdk\platform-tools\adb.exe'

function Nodes { Get-UiNodes -Serial $Serial }
function State([string]$label) { Show-State -Serial $Serial -Label $label }
function Tap([int]$x, [int]$y, [string]$what) {
    Write-Host "  tap $what ($x,$y)"
    & $adb -s $Serial shell input tap $x $y | Out-Null
    Start-Sleep -Seconds 2
}

& $adb -s $Serial shell am force-stop com.inhuman | Out-Null
& $adb -s $Serial shell am start -n 'com.inhuman/.MainActivity' | Out-Null
Start-Sleep -Seconds 5

Write-Host '=== the list, with two conversations ==='
$rows = (Nodes | Where-Object { $_.Desc -eq 'Delete conversation' })
Write-Host "  rows: $($rows.Count)"
$first = $rows[0]
$second = if ($rows.Count -ge 2) { $rows[1] } else { $null }
Write-Host "  row 1 delete at y=$($first.Y1)"
if ($second) { Write-Host "  row 2 delete at y=$($second.Y1)" }

Write-Host ''
Write-Host '=== open it on row 1 ==='
Tap ([int](($first.X1 + $first.X2) / 2)) ([int](($first.Y1 + $first.Y2) / 2)) 'delete, row 1'
$open = State 'opened on row 1'

Write-Host ''
Write-Host '=== tap well away from the dialog and from any row ==='
# Row 1's delete sits near the top; the dialog sits low. A point in the middle
# of the empty area below row 2 is neither.
Tap 540 1000 'empty space (no row, no dialog)'
$outside = State 'after tapping outside'

Write-Host ''
Write-Host '=== the comparison the report is about ==='
Write-Host "  title before: '$($open.Title)'"
Write-Host "  title after : '$($outside.Title)'"
Write-Host "  CHANGED     : $($open.Title -ne $outside.Title)"
Write-Host "  body before : '$($open.Body)'"
Write-Host "  body after  : '$($outside.Body)'"
Write-Host "  BODY CHANGED: $($open.Body -ne $outside.Body)"

Write-Host ''
Write-Host '=== and what does it point at now, if still open? ==='
if ($outside.Dialog) {
    $now = Nodes | Where-Object { $_.Desc -eq 'Delete conversation' }
    Write-Host "  rows reachable behind it: $($now.Count) (0 means the scrim is up)"
    Tap 540 1900 'the scrim, low on the screen'
    State 'after tapping the scrim'
}
