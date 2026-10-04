# How much of the screen the UI actually occupies.
#
# The earlier density pass measured gaps *inside* a screen -- the space between
# elements -- and concluded things were tight. That missed the larger question,
# which is how much of the display the interface uses at all. A dialog can have
# perfectly tight internal spacing and still occupy 40% of a 1080px-wide screen
# with dead margins either side.
#
# So this reports both: the content's width and height as a share of the screen,
# and the margins around it.
param([string]$Serial = 'emulator-5554', [string]$Label = 'screen')
$ErrorActionPreference = 'Continue'
Import-Module "$PSScriptRoot\UiDump.psm1" -Force
$adb = 'C:\android-sdk\platform-tools\adb.exe'

$size = (& $adb -s $Serial shell wm size 2>&1) -join ''
$sw = 0; $sh = 0
if ($size -match '(\d+)x(\d+)') { $sw = [int]$Matches[1]; $sh = [int]$Matches[2] }
$density = [int](((& $adb -s $Serial shell wm density 2>&1) -join '') -replace '\D', '')

$nodes = Get-UiNodes -Serial $Serial | Where-Object { $_.Text -or $_.Desc }
if ($nodes.Count -eq 0) { Write-Host "  [$Label] nothing"; return }

$x1 = ($nodes | ForEach-Object { $_.X1 } | Measure-Object -Minimum).Minimum
$x2 = ($nodes | ForEach-Object { $_.X2 } | Measure-Object -Maximum).Maximum
$y1 = ($nodes | ForEach-Object { $_.Y1 } | Measure-Object -Minimum).Minimum
$y2 = ($nodes | ForEach-Object { $_.Y2 } | Measure-Object -Maximum).Maximum

Write-Host ""
Write-Host "=== $Label ==="
Write-Host ("  screen        {0}x{1}px  ({2}dpi, {3}dp wide)" -f $sw, $sh, $density, [math]::Round($sw * 160.0 / $density))
Write-Host ("  content spans x={0}..{1}  y={2}..{3}" -f $x1, $x2, $y1, $y2)
Write-Host ("  WIDTH  used   {0}px of {1}  = {2}%" -f ($x2 - $x1), $sw, [math]::Round(100.0 * ($x2 - $x1) / $sw))
Write-Host ("  HEIGHT used   {0}px of {1}  = {2}%" -f ($y2 - $y1), $sh, [math]::Round(100.0 * ($y2 - $y1) / $sh))
Write-Host ("  margins       left {0}px, right {1}px, top {2}px, bottom {3}px" -f $x1, ($sw - $x2), $y1, ($sh - $y2))

# The widest node is usually the container that decides the layout, so naming it
# says which element is setting the width.
$widest = $nodes | Sort-Object { $_.X2 - $_.X1 } -Descending | Select-Object -First 3
Write-Host "  widest elements:"
foreach ($w in $widest) {
    $t = if ($w.Text) { $w.Text } elseif ($w.Desc) { $w.Desc } else { '(no label)' }
    if ($t.Length -gt 34) { $t = $t.Substring(0, 34) }
    Write-Host ("    {0,5}px  x={1}..{2}  '{3}'" -f ($w.X2 - $w.X1), $w.X1, $w.X2, $t)
}
