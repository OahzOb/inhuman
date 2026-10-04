# Measure how much of a screen is actually used.
#
# "Too much empty space" is a judgement; what it is a judgement *about* is
# measurable. This reads the node tree and reports, per screen, the vertical
# extent of content against the viewport, the biggest gaps between adjacent
# elements, and the paddings that produced them.
#
# Without this the temptation is to shrink things that were never the problem.
param([string]$Serial = 'emulator-5554')
$ErrorActionPreference = 'Continue'
Import-Module "$PSScriptRoot\UiDump.psm1" -Force
$adb = 'C:\android-sdk\platform-tools\adb.exe'

function Measure-Screen {
    param([string]$Label, [string]$Serial = 'emulator-5554')
    $nodes = Get-UiNodes -Serial $Serial |
        Where-Object { $_.Text -or $_.Desc } |
        Sort-Object Y1

    if ($nodes.Count -eq 0) { Write-Host "  [$Label] nothing on screen"; return }

    $screenH = [int]((& $adb -s $Serial shell wm size) -replace '.*?(\d+)x(\d+).*', '$2')
    $screenW = [int]((& $adb -s $Serial shell wm size) -replace '.*?(\d+)x(\d+).*', '$1')
    $top = $nodes[0].Y1
    $bottom = ($nodes | ForEach-Object { $_.Y2 } | Measure-Object -Maximum).Maximum
    $used = $bottom - $top

    Write-Host ""
    Write-Host "=== $Label ==="
    Write-Host ("  screen      {0}x{1}" -f $screenW, $screenH)
    Write-Host ("  content     y={0}..{1}  ({2}px, {3}% of height)" -f $top, $bottom, $used, [math]::Round(100.0 * $used / $screenH))
    Write-Host ("  empty below {0}px, above {1}px" -f ($screenH - $bottom), $top)

    # Gaps between one element's bottom and the next one's top, where they do
    # not overlap. Large ones are what "wasted space" actually looks like.
    $gaps = @()
    for ($i = 1; $i -lt $nodes.Count; $i++) {
        $prev = $nodes[$i - 1]
        $cur = $nodes[$i]
        if ($cur.Y1 -gt $prev.Y2) {
            $gaps += [pscustomobject]@{
                Gap = $cur.Y1 - $prev.Y2
                After = if ($prev.Text) { $prev.Text } else { $prev.Desc }
                Before = if ($cur.Text) { $cur.Text } else { $cur.Desc }
                Y = $prev.Y2
            }
        }
    }
    $big = $gaps | Where-Object { $_.Gap -ge 40 } | Sort-Object Gap -Descending | Select-Object -First 6
    if ($big) {
        Write-Host "  largest gaps:"
        $big | ForEach-Object {
            $a = if ($_.After.Length -gt 26) { $_.After.Substring(0, 26) } else { $_.After }
            $b = if ($_.Before.Length -gt 26) { $_.Before.Substring(0, 26) } else { $_.Before }
            Write-Host ("    {0,4}px at y={1,-5} between '{2}' and '{3}'" -f $_.Gap, $_.Y, $a, $b)
        }
    } else {
        Write-Host "  no gap over 40px"
    }

    # Height of each label and body row, which is where density comes from.
    $rows = $nodes | Where-Object { $_.Text } | Select-Object -First 40
    if ($rows) {
        $avg = [math]::Round((($rows | ForEach-Object { $_.Y2 - $_.Y1 }) | Measure-Object -Average).Average)
        Write-Host "  text rows: $($rows.Count), average height ${avg}px"
    }
}

# Each screen is reached by hand; this measures whatever is up.
Measure-Screen -Label $args[0] -Serial $Serial
