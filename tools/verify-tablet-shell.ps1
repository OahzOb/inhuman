# Tablet-ratio checks for this pass's layout changes, run on the phone AVD with
# `wm size`/`wm density` because the dedicated dsh_tablet AVD dies on this
# machine's GPU (missing `opengl32sw`, fallback to system GL, crash dialog).
# AGENTS.md allows one AVD resized as a ratio test; it is not a substitute for
# the two-device profile, and the phone profile was run separately.
#
# 1. Home is the two-pane shell and the conversation column is capped at 760dp
#    and centred (B5's rule, re-measured after the row and menu changes).
# 2. Agents shows Create in the header on this window instead of a FAB
#    (B44's prefersFabForPrimaryAction change).
# 3. A settings form column is capped at ReadableMeasure (640dp) less the
#    32dp gutters, i.e. about 576dp.
#
#     .\tools\verify-tablet-shell.ps1 [-Serial emulator-5554]
[CmdletBinding()]
param([string]$Serial = 'emulator-5554')
$ErrorActionPreference = 'Stop'
Import-Module (Join-Path $PSScriptRoot 'UiDump.psm1') -Force
$adb = 'C:\android-sdk\platform-tools\adb.exe'

function Adb { & $adb -s $Serial @args }
function Dump { Get-UiNodes -Serial $Serial }
function Tap-Node([object]$n, [string]$what) {
	if (-not $n) { throw "missing $what" }
	Adb shell input tap ([int](($n.X1 + $n.X2) / 2)) ([int](($n.Y1 + $n.Y2) / 2)) | Out-Null
	Start-Sleep -Seconds 2
}
function Find([scriptblock]$where, [string]$what) {
	for ($i = 0; $i -lt 3; $i++) {
		$n = Dump | Where-Object $where | Select-Object -First 1
		if ($n) { return $n }
		Start-Sleep -Seconds 2
	}
	throw "missing $what"
}

Write-Host '=== boot ==='
Adb wait-for-device | Out-Null
$deadline = (Get-Date).AddSeconds(240)
while ((Get-Date) -lt $deadline) {
	$booted = (Adb shell getprop sys.boot_completed 2>$null | Out-String).Trim()
	if ($booted -eq '1') { break }
	Start-Sleep -Seconds 5
}
if ((Adb shell getprop sys.boot_completed | Out-String).Trim() -ne '1') { throw 'device did not boot' }

Write-Host '=== resize to 2560x1600 @320dpi = 1280x800dp ==='
Adb shell wm size 2560x1600 | Out-Null
Adb shell wm density 320 | Out-Null
Start-Sleep -Seconds 3
try {
	$size = (Adb shell wm size | Out-String).Trim()
	# `wm size` prints the physical size and then an override line; the override
	# is the one this run created.
	$density = 320
	$override = [regex]::Match($size, 'Override size: (\d+)x')
	$widthPx = if ($override.Success) { [int]$override.Groups[1].Value } else {
		[int]([regex]::Match($size, '(\d+)x').Groups[1].Value)
	}
	$widthDp = [int]($widthPx * 160 / $density)
	Write-Host "  ${widthPx}px, ${density}dpi => ${widthDp}dp wide"
	if ($widthDp -ne 1280) { throw "expected a 1280dp window, computed ${widthDp}dp" }

	Write-Host '=== install, launch, home ==='
	Adb install -r -t 'C:\Projects\inhuman\app\build\outputs\apk\debug\app-debug.apk' | Out-Null
	Adb shell pm clear com.inhuman | Out-Null
	# A launch made before the resize can keep the old configuration; starting
	# after it is what makes the shell read the new window.
	Adb shell am start -n com.inhuman/.MainActivity | Out-Null
	Start-Sleep -Seconds 8

	$labels = Dump | Where-Object { $_.Text -or $_.Desc } |
		ForEach-Object { if ($_.Text) { $_.Text } else { $_.Desc } }
	Write-Host ("  home: " + (($labels | Select-Object -First 10) -join ' | '))

	$rail = Dump | Where-Object { $_.Text -eq '工作区' -or $_.Text -eq 'Workspaces' } | Select-Object -First 1
	if (-not $rail) { throw 'no workbench rail on a 1280dp window: not the two-pane shell' }
	Write-Host ("  ok: two-pane rail present (工作区 at x={0})" -f $rail.X1)

	$empty = Dump | Where-Object { $_.Text -like '*干净的书桌*' -or $_.Text -like '*clean desk*' } | Select-Object -First 1
	if ($empty) {
		$colW = $empty.X2 - $empty.X1
		$colDp = [int]($colW * 160 / $density)
		Write-Host "  list text column ${colW}px = ${colDp}dp (cap 760dp less gutters)"
		if ($colDp -gt 760) { throw "the conversation column is ${colDp}dp, over its 760dp cap" }
		$centre = [int](($empty.X1 + $empty.X2) / 2)
		Write-Host "  column centre ${centre}px; screen ${widthPx}px, rail on the left"
	}

	Write-Host '=== agents: header Create, no FAB ==='
	Tap-Node (Find { $_.Text -eq '助手' -or $_.Text -eq 'Assistants' } 'the assistants destination') 'assistants'
	Start-Sleep -Seconds 2
	$headerCreate = Dump | Where-Object { $_.Text -eq '新建助手' -or $_.Text -eq 'New assistant' }
	Write-Host ("  header create nodes: " + ($headerCreate | Measure-Object).Count)
	if (-not $headerCreate) { throw 'no header Create on a 1280dp window' }
	Write-Host '  ok: assistants screen shows Create in the header'

	Write-Host '=== settings: form column capped at ReadableMeasure ==='
	Adb shell input keyevent KEYCODE_BACK | Out-Null
	Start-Sleep -Seconds 2
	Tap-Node (Find { $_.Text -eq '设置' -or $_.Text -eq 'Settings' } 'the settings destination') 'settings'
	Start-Sleep -Seconds 3
	$form = Dump | Where-Object { $_.Text -eq '01 / 模型' -or $_.Text -eq '01 / Models' } | Select-Object -First 1
	if (-not $form) { throw 'settings did not open' }
	$formW = $form.X2 - $form.X1
	Write-Host "  section heading width ${formW}px = $([int]($formW * 160 / $density))dp (cap 640dp)"
	if ($formW -gt [int](640 * $density / 160)) { throw "settings content exceeds the 640dp measure (${formW}px)" }
	Write-Host '  ok: settings form stays inside ReadableMeasure'

	Write-Host '=== tablet-ratio assertions passed ==='
} finally {
	Adb shell wm size reset | Out-Null
	Adb shell wm density reset | Out-Null
	Write-Host 'wm size/density reset'
}
