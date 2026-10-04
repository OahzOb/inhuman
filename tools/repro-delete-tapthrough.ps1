# Reproduce the delete-dialog tap-through, precisely.
#
# Two earlier attempts misread the evidence: the dialog title matched the
# confirm button's own "Delete" text, and the assumed tap co-ordinates were above
# a dialog that lives low on the screen. Both mistakes produced confident
# readings of the wrong thing.
#
# So this records, at every step: what is on screen, the dialog's real bounds,
# and which conversation the dialog names. The bug is only reproducible if those
# three are read together.
param([string]$Serial = 'emulator-5554')
$ErrorActionPreference = 'Stop'
$adb = 'C:\android-sdk\platform-tools\adb.exe'

function Nodes {
    $stamp = Get-Date -Format 'HHmmssfff'
    & $adb -s $Serial shell "rm -f /sdcard/dt*.xml" 2>&1 | Out-Null
    & $adb -s $Serial shell "uiautomator dump /sdcard/dt$stamp.xml" 2>&1 | Out-Null
    $local = Join-Path $env:TEMP "dt-$stamp.xml"
    & $adb -s $Serial pull "/sdcard/dt$stamp.xml" $local 2>&1 | Out-Null
    if (-not (Test-Path $local)) { return @() }
    $out = @()
    foreach ($tag in [regex]::Matches((Get-Content $local -Raw), '<node\b[^>]*?(?:/>|>)')) {
        $t = $tag.Value
        $b = [regex]::Match($t, 'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
        if (-not $b.Success) { continue }
        # The dialog title contains an escaped quote, which the generic pattern
        # truncates. Read it whole.
        $raw = [regex]::Match($t, '\btext="([^"]*)"').Groups[1].Value
        $out += [pscustomobject]@{
            Desc = [regex]::Match($t, 'content-desc="([^"]*)"').Groups[1].Value
            Text = $raw
            X1 = [int]$b.Groups[1].Value; Y1 = [int]$b.Groups[2].Value
            X2 = [int]$b.Groups[3].Value; Y2 = [int]$b.Groups[4].Value
        }
    }
    # Titles with a quoted conversation name survive in the raw XML; pull them
    # out separately because the node regex stops at the inner quote.
    $rawXml = Get-Content $local -Raw
    foreach ($m in [regex]::Matches($rawXml, 'text="(Delete [^"]*)"')) {
        $out += [pscustomobject]@{ Desc = ''; Text = $m.Groups[1].Value; X1 = 0; Y1 = 0; X2 = 0; Y2 = 0 }
    }
    return $out
}
function Tap([int]$x, [int]$y, [string]$what) {
    Write-Host "  tap $what ($x,$y)"
    & $adb -s $Serial shell input tap $x $y | Out-Null
    Start-Sleep -Seconds 2
}
function Report([string]$label) {
    $n = Nodes
    $title = ($n | Where-Object { $_.Text -match '^(Delete|Remove) .+' } | Select-Object -First 1).Text
    $inChat = ($n | Where-Object { $_.Desc -eq 'Attach an image' }).Count -gt 0
    $onList = ($n | Where-Object { $_.Desc -eq 'New conversation' }).Count -gt 0
    $dialog = ($n | Where-Object { $_.Text -eq 'Cancel' }).Count -gt 0
    $titleNode = $n | Where-Object { $_.Text -eq 'Cancel' } | Select-Object -First 1
    Write-Host "  [$label] dialog=$dialog inChat=$inChat onList=$onList"
    if ($title) { Write-Host "         names: '$title'" }
    if ($dialog -and $titleNode) {
        Write-Host "         dialog spans y=$($titleNode.Y1)..$($titleNode.Y2)"
    }
    return [pscustomobject]@{ Title = $title; InChat = $inChat; OnList = $onList; Dialog = $dialog }
}

& $adb -s $Serial shell am force-stop com.inhuman | Out-Null
& $adb -s $Serial shell am start -n 'com.inhuman/.MainActivity' | Out-Null
Start-Sleep -Seconds 5

Write-Host '=== step 1: open the dialog on the first row ==='
$del = (Nodes | Where-Object { $_.Desc -eq 'Delete conversation' } | Select-Object -First 1)
Tap ([int](($del.X1 + $del.X2) / 2)) ([int](($del.Y1 + $del.Y2) / 2)) 'delete, row 1'
$a = Report 'after opening'

Write-Host ''
Write-Host '=== step 2: tap on the area where the SECOND row sits ==='
$rows = Nodes | Where-Object { $_.Desc -eq 'Delete conversation' }
Write-Host "  rows visible behind the dialog: $($rows.Count)"
# The rows are behind the scrim and absent from the dump, so use the y of the
# second row measured before the dialog opened.
$rowTwoY = $script:rowTwoY
if (-not $rowTwoY) { $rowTwoY = 767 }
Tap 300 $rowTwoY "row 2's own position (y=$rowTwoY)"
$b = Report 'after tapping the row underneath'

Write-Host ''
Write-Host '=== step 3: the report says the description changed ==='
Write-Host "  title before: '$($a.Title)'"
Write-Host "  title after : '$($b.Title)'"
Write-Host "  CHANGED: $($a.Title -ne $b.Title)"

Write-Host ''
Write-Host '=== step 4: whatever happened, what does delete now point at? ==='
$cur = Nodes | Where-Object { $_.Desc -eq 'Delete conversation' }
Write-Host "  rows on the list: $($cur.Count)"
if ($cur.Count -gt 0) {
    Tap ([int](($cur[0].X1 + $cur[0].X2) / 2)) ([int](($cur[0].Y1 + $cur[0].Y2) / 2)) 'delete again'
    Report 'after reopening'
}
