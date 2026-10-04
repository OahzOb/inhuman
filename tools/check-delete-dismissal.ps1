# Two taps, and what each one should do.
#
# Worth separating, because the first attempt conflated them and then drew the
# wrong conclusion twice:
#
#   - A tap *inside* the dialog (on its body) should do nothing at all.
#   - A tap on the *scrim* outside it should close it, and closing must clear
#     everything the dialog was showing -- not just the cached file count, which
#     is what the bug did, leaving the dialog open with its body text changed
#     from "its folder is empty" to the fallback wording.
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
function OpenDialogOnFirstRow {
    $del = (Nodes | Where-Object { $_.Desc -eq 'Delete conversation' } | Select-Object -First 1)
    if (-not $del) { throw 'no delete button on screen' }
    Tap ([int](($del.X1 + $del.X2) / 2)) ([int](($del.Y1 + $del.Y2) / 2)) 'delete, row 1'
}

& $adb -s $Serial shell am force-stop com.inhuman | Out-Null
& $adb -s $Serial shell am start -n 'com.inhuman/.MainActivity' | Out-Null
Start-Sleep -Seconds 5

Write-Host '=== A. a tap INSIDE the dialog must change nothing ==='
OpenDialogOnFirstRow
$a1 = State 'opened'
# The dialog spans roughly y=800..1600 in the middle of the screen, so this is
# inside it and not on a button.
Tap 540 1100 'inside the dialog body'
$a2 = State 'after a tap inside'
Write-Host "  title unchanged : $($a1.Title -eq $a2.Title)"
Write-Host "  body  unchanged : $($a1.Body -eq $a2.Body)"
Write-Host "  still open      : $($a2.Dialog)"

Write-Host ''
Write-Host '=== B. a tap on the SCRIM must close it, cleanly ==='
# Only needed if A somehow closed it.
$still = State 'before B'
if (-not $still.Dialog) { OpenDialogOnFirstRow; $still = State 'reopened' }
Tap 540 1900 'the scrim below the dialog'
$b = State 'after a tap on the scrim'
Write-Host "  closed          : $(-not $b.Dialog)"
Write-Host "  list is back    : $($b.Rows -gt 0)"

Write-Host ''
Write-Host '=== C. reopening names the same conversation ==='
OpenDialogOnFirstRow
$c = State 'reopened on row 1'
Write-Host "  same title as A : $($a1.Title -eq $c.Title)"
Write-Host "  same body  as A : $($a1.Body -eq $c.Body)"

Write-Host ''
Write-Host '=== D. deleting actually removes the row and closes ==='
$before = (Nodes | Where-Object { $_.Desc -eq 'Delete conversation' }).Count
$confirm = (Nodes | Where-Object { $_.Text -eq 'Delete' } | Where-Object { $_.Y1 -gt 1300 } | Select-Object -First 1)
if ($confirm) {
    Tap ([int](($confirm.X1 + $confirm.X2) / 2)) ([int](($confirm.Y1 + $confirm.Y2) / 2)) 'Delete (confirm)'
    Start-Sleep -Seconds 3
    $after = State 'after confirming'
    Write-Host "  rows: $before -> $($after.Rows)"
    Write-Host "  dialog closed: $(-not $after.Dialog)"
} else {
    Write-Host '  could not find the confirm button'
}
