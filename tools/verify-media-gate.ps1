# The media configuration gate on a device (docs/07 B46): a fresh install has no
# media backend, so none of the six media tools may appear in the assistant
# editor's tool list while ordinary rows render.
#
# The positive half -- configuring a backend makes exactly that capability's
# names appear -- is unit-tested (`MediaAvailabilityTest`, and
# `ConversationToolsTest`'s "a configured media tool is offered and selectable").
# It is NOT driven here, and the reason is the emulator, not the app: this image's
# Gboard is in Chinese mode and converts `:` `/` `.` to full-width characters, so
# the media dialog's https URL cannot be typed (a Direct backend never validates),
# and the provider-key path did not complete either. That is the same class of
# input-injection problem as docs/07 D13; rerun with a Latin IME to cover it.
#
#     .\tools\verify-media-gate.ps1 [-Serial emulator-5554]
[CmdletBinding()]
param([string]$Serial = 'emulator-5554')
$ErrorActionPreference = 'Stop'
Import-Module (Join-Path $PSScriptRoot 'UiDump.psm1') -Force
$adb = 'C:\android-sdk\platform-tools\adb.exe'
$mediaNames = @(
	'decide', 'transcribe', 'generate_image', 'generate_video', 'video_status', 'speak'
)

function Adb { & $adb -s $Serial @args }
function Dump { Get-UiNodes -Serial $Serial }
function Labels { Dump | Where-Object { $_.Text -or $_.Desc } |
	ForEach-Object { if ($_.Text) { $_.Text } else { $_.Desc } } }
function Tap-Node([object]$n, [string]$what) {
	if (-not $n) { throw "missing $what" }
	Adb shell input tap ([int](($n.X1 + $n.X2) / 2)) ([int](($n.Y1 + $n.Y2) / 2)) | Out-Null
	Start-Sleep -Seconds 2
}
function Find-First([scriptblock]$where, [string]$what, [int]$tries = 4) {
	for ($i = 0; $i -lt $tries; $i++) {
		$n = Dump | Where-Object $where | Select-Object -First 1
		if ($n) { return $n }
		Start-Sleep -Seconds 2
	}
	throw "missing $what"
}
# The header folds its destinations into the overflow menu on a compact window
# with large text; this reaches either shape.
function Open-Destination([string]$name) {
	$direct = Dump | Where-Object { $_.Text -eq $name -or $_.Desc -eq $name } | Select-Object -First 1
	if ($direct) { Tap-Node $direct $name; return }
	$more = Dump | Where-Object { $_.Desc -match '更多入口|More' } | Select-Object -First 1
	if (-not $more) { throw "no destination '$name' and no overflow entry" }
	Tap-Node $more 'the overflow menu'
	Start-Sleep -Seconds 1
	Tap-Node (Find-First { $_.Text -eq $name -or $_.Desc -eq $name } $name) $name
}
# The editor's tool list is longer than a phone screen; the media rows sit among
# the write tools. Swipes start on a row, never on the dialog's button bar.
function Collect-Scrolled([int]$x = 540, [int]$top = 800, [int]$bottom = 1600, [int]$swipes = 14) {
	$seen = [System.Collections.Generic.HashSet[string]]::new()
	foreach ($l in Labels) { [void]$seen.Add($l) }
	for ($i = 0; $i -lt $swipes; $i++) {
		Adb shell input swipe $x $bottom $x $top 300 | Out-Null
		Start-Sleep -Milliseconds 700
		foreach ($l in Labels) { [void]$seen.Add($l) }
	}
	return $seen
}

Write-Host '=== install and start clean ==='
Adb shell settings put system font_scale 1.0 | Out-Null
Adb install -r -t 'C:\Projects\inhuman\app\build\outputs\apk\debug\app-debug.apk' | Out-Null
Adb shell pm clear com.inhuman | Out-Null
Adb shell am start -n com.inhuman/.MainActivity | Out-Null
Start-Sleep -Seconds 7

Write-Host '=== assistant editor, nothing configured ==='
Open-Destination '助手'
Tap-Node (Find-First { $_.Text -eq 'Assistant' -or $_.Text -eq '默认' } 'the default assistant row') 'the default assistant'
Start-Sleep -Seconds 2
$seen = Collect-Scrolled
if (-not $seen.Contains('read_file')) {
	throw "the tool list did not render (read_file missing). Seen: $($seen -join ', ')"
}
foreach ($name in $mediaNames) {
	if ($seen.Contains($name)) { throw "$name should be absent with no media backend configured" }
}
Write-Host "  ok: all six media tools are absent; ordinary rows (read_file) render"

Write-Host '=== media gate absence assertion passed ==='
