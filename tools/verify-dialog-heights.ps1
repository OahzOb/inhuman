# Drive the chat's dialog family on a 640x360dp landscape window and assert
# that every dialog keeps its action row on the screen.
#
# The defect this pins (docs/07 B38): AlertDialog bodies capped at fixed
# 380-520dp pushed their own buttons below a short window, and the only
# reachable gesture -- a tap outside -- declines. The unit of verification is
# geometry, so the driver only needs to *reach* the dialogs: no API key, no
# model, no turn. The dialogs it reaches (model picker, conversation tools,
# snapshots) share the rewritten slot arithmetic with every other
# `dialogBodyMaxHeight` call site, including the SSH consent's Allow, which
# cannot be driven without a live host.
#
#     .\tools\verify-dialog-heights.ps1                  # emulator-5554
#     .\tools\verify-dialog-heights.ps1 -Serial <serial> -Label my-run
#
# Leaves screenshots in .shots/. Restores `wm size`/`wm density` on exit; a
# failed run leaves the override in place so the state it failed in can be
# inspected -- rerun with -NoResize to skip the resize entirely.
[CmdletBinding()]
param(
	[string]$Serial = 'emulator-5554',
	[string]$Label = 'dlg',
	[switch]$NoResize
)
$ErrorActionPreference = 'Stop'
$adb = 'C:\android-sdk\platform-tools\adb.exe'
$shotDir = 'C:\Projects\inhuman\.shots'
New-Item -ItemType Directory -Force -Path $shotDir | Out-Null

# 1280x720 px @ 320dpi == 640x360dp: the matrix's short-landscape case.
$Script:ScreenW = 1280
$Script:ScreenH = 720

function Adb { & $adb -s $Serial @args }
function Get-Nodes {
	Adb @('shell', 'uiautomator', 'dump', '/sdcard/ui.xml') 2>&1 | Out-Null
	$local = Join-Path $env:TEMP "u-$Serial.xml"
	Adb @('pull', '/sdcard/ui.xml', $local) 2>&1 | Out-Null
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
	Adb @('shell', 'input', 'tap', "$x", "$y") | Out-Null
	Start-Sleep -Seconds 2
}
function Tap-Find([string]$p, [string]$what) {
	$n = Get-Nodes | Where-Object { ($_.Desc -match $p) -or ($_.Text -match $p) } |
		Sort-Object Y1 | Select-Object -First 1
	if (-not $n) { Write-Host "  [$what] missing; screen:"; Labels | ForEach-Object { Write-Host "    '$_'" }; throw "missing $what" }
	Tap-Node $n $what
}
function Save-Shot([string]$n) {
	$png = Join-Path $shotDir "$Label-$n.png"
	Adb @('shell', 'screencap', '-p', '/sdcard/s.png') | Out-Null
	Adb @('pull', '/sdcard/s.png', $png) 2>&1 | Out-Null
	Write-Host "  shot: $Label-$n"
}
function Assert-Visible([string]$p, [string]$what) {
	$n = Get-Nodes | Where-Object { ($_.Text -match $p) -or ($_.Desc -match $p) } | Select-Object -First 1
	if (-not $n) { Write-Host "  [$what] not on screen; labels:"; Labels | ForEach-Object { Write-Host "    '$_'" }; throw "missing $what" }
	if ($n.Y2 -gt $Script:ScreenH -or $n.Y1 -lt 0) {
		throw "$what is off-screen: bounds [$($n.X1),$($n.Y1)][$($n.X2),$($n.Y2)] vs screen height $($Script:ScreenH)"
	}
	Write-Host ("  ok {0}: [{1},{2}][{3},{4}]" -f $what, $n.X1, $n.Y1, $n.X2, $n.Y2)
}
function Report-Offscreen {
	$off = Get-Nodes | Where-Object { ($_.Text -or $_.Desc) -and ($_.Y2 -gt $Script:ScreenH -or $_.Y1 -lt 0) }
	if ($off) {
		$off | ForEach-Object {
			$name = if ($_.Text) { $_.Text } else { $_.Desc }
			Write-Host ("  OFF-SCREEN: '{0}' [{1},{2}][{3},{4}]" -f $name, $_.X1, $_.Y1, $_.X2, $_.Y2)
		}
		throw "$($off.Count) node(s) off-screen"
	}
}

Write-Host '=== boot ==='
Adb @('wait-for-device') | Out-Null
$deadline = (Get-Date).AddSeconds(150)
while ((Get-Date) -lt $deadline) {
	$booted = (Adb @('shell', 'getprop', 'sys.boot_completed') 2>$null | Out-String).Trim()
	if ($booted -eq '1') { break }
	Start-Sleep -Seconds 5
}
if ((Adb @('shell', 'getprop', 'sys.boot_completed') | Out-String).Trim() -ne '1') { throw 'emulator did not boot' }
Write-Host '  booted'

try {
	if (-not $NoResize) {
		Write-Host "=== window: 1280x720 @320dpi == 640x360dp ==="
		Adb @('shell', 'wm', 'size', '1280x720') | Out-Null
		Adb @('shell', 'wm', 'density', '320') | Out-Null
		Start-Sleep -Seconds 2
	}

	Write-Host '=== install and launch ==='
	Adb @('install', '-r', '-t', 'C:\Projects\inhuman\app\build\outputs\apk\debug\app-debug.apk') | ForEach-Object { Write-Host "  $_" }
	Adb @('shell', 'pm', 'clear', 'com.inhuman') | Out-Null
	Adb @('shell', 'am', 'start', '-n', 'com.inhuman/.MainActivity') | Out-Null
	Start-Sleep -Seconds 6
	Save-Shot 'home'
	Write-Host '  home labels:'
	Labels | ForEach-Object { Write-Host "    '$_'" }

	Write-Host '=== new conversation ==='
	Tap-Find '^新建$|New' 'Create (opens the create sheet)'
	Start-Sleep -Seconds 2
	Tap-Find '^新建对话$|^New conversation$' 'the new-conversation row'
	Start-Sleep -Seconds 3
	Save-Shot 'chat'
	Write-Host '  chat labels:'
	Labels | ForEach-Object { Write-Host "    '$_'" }

	Write-Host '=== model picker (from the chat title) ==='
	Tap-Find '^New conversation$|^新对话$' 'the chat title (opens the model picker)'
	Start-Sleep -Seconds 3
	Save-Shot 'model-picker'
	Assert-Visible '完成|Done|取消|Cancel' 'model picker action row'
	Report-Offscreen
	Adb @('shell', 'input', 'keyevent', 'KEYCODE_BACK') | Out-Null
	Start-Sleep -Seconds 2

	Write-Host '=== the tool sheet (via the workbench) ==='
	Tap-Find '更多|More' 'the More button'
	Save-Shot 'workbench'
	Tap-Find '本会话工具|Tools' 'the conversation-tools entry'
	Start-Sleep -Seconds 2
	Save-Shot 'tools-sheet'
	Assert-Visible '应用|Apply|完成|Done' 'tool sheet apply'
	Assert-Visible '取消|Cancel' 'tool sheet cancel'
	Report-Offscreen
	Adb @('shell', 'input', 'keyevent', 'KEYCODE_BACK') | Out-Null
	Start-Sleep -Seconds 2

	Write-Host '=== the snapshot list (workbench) ==='
	# One BACK closes the tool sheet only. If it also closed the workbench,
	# reopen it from the chat bar rather than guessing the back stack.
	$nodes = Get-Nodes
	$inWorkbench = $nodes | Where-Object {
		($_.Text -match '检查与设置|文件与导出') -or ($_.Desc -match '检查与设置|文件与导出')
	}
	if (-not $inWorkbench) {
		Tap-Find '更多|More' 'the More button'
		Start-Sleep -Seconds 2
	}
	# The checkpoint entry lives in the workbench's Inspect & settings
	# section. Filter to that tab first so the short window does not have to
	# scroll the whole action list, then swipe if it is still below the fold.
	Tap-Find '^检查与设置$|^Inspect' 'the Inspect & settings tab'
	Start-Sleep -Seconds 2
	# The checkpoint entry ("回滚快照") is the second row of this section;
	# on a short window only the first row fits. Swipe from the first row's
	# centre (a swipe from the scrim below the dialog scrolls nothing).
	$row = Get-Nodes | Where-Object { $_.Text -match '^会话记忆$|^Session memory$' } |
		Select-Object -First 1
	$startY = if ($row) { [int](($row.Y1 + $row.Y2) / 2) } else { 460 }
	$found = $false
	for ($i = 0; $i -lt 5 -and -not $found; $i++) {
		$found = [bool](Get-Nodes | Where-Object {
			($_.Text -match '回滚|还原|快照|checkpoint|snapshot') -or ($_.Desc -match '回滚|还原|快照|checkpoint|snapshot')
		})
		if (-not $found) {
			Adb @('shell', 'input', 'swipe', '640', "$startY", '640', '200', '600') | Out-Null
			Start-Sleep -Seconds 1
		}
	}
	Tap-Find '回滚|还原|快照|checkpoint|snapshot' 'the snapshot entry'
	Start-Sleep -Seconds 2
	Save-Shot 'snapshots'
	Assert-Visible '取消|Cancel|关闭|Close' 'snapshot dialog cancel'
	Report-Offscreen

	Write-Host '=== all dialogs keep their buttons on a 640x360dp window ==='
} finally {
	if (-not $NoResize) {
		Adb @('shell', 'wm', 'size', 'reset') | Out-Null
		Adb @('shell', 'wm', 'density', 'reset') | Out-Null
		Write-Host 'wm size/density reset'
	}
}
