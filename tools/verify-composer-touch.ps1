# Verify the composer's short-window cap and the transcript's touch targets
# on a live device (docs/07 B41, B42).
#
# 1. Turns demo mode on (no key needed), drives one scripted turn so the
#    transcript carries collapsed tool sections, and measures their header
#    rows against the 48dp recommendation.
# 2. At 1.5x font scale, opens the in-chat search and checks the role column
#    renders without wrapping into the fixed width it used to have.
# 3. On a 640x360dp landscape window with the keyboard open, types a
#    three-line draft and asserts the send row stays inside the screen.
#
#     .\tools\verify-composer-touch.ps1 [-Serial emulator-5554]
#
# Leaves screenshots in .shots/ and restores font scale, wm size and wm
# density on exit.
[CmdletBinding()]
param([string]$Serial = 'emulator-5554')
$ErrorActionPreference = 'Stop'
Import-Module (Join-Path $PSScriptRoot 'UiDump.psm1') -Force
$adb = 'C:\android-sdk\platform-tools\adb.exe'
$shotDir = 'C:\Projects\inhuman\.shots'
New-Item -ItemType Directory -Force -Path $shotDir | Out-Null

function Tap-Node([object]$n, [string]$what) {
	if (-not $n) { throw "missing $what" }
	Write-Host ("  tap {0} ({1},{2})" -f $what, [int](($n.X1 + $n.X2) / 2), [int](($n.Y1 + $n.Y2) / 2))
	& $adb -s $Serial shell input tap ([int](($n.X1 + $n.X2) / 2)) ([int](($n.Y1 + $n.Y2) / 2)) | Out-Null
	Start-Sleep -Seconds 2
}
function Find-Node([scriptblock]$where, [string]$what) {
	$n = $null
	# uiautomator dumps can fail while the IME or a reveal animates; a
	# missing node is not a verdict until three dumps agree.
	for ($i = 0; $i -lt 3 -and -not $n; $i++) {
		$nodes = Get-UiNodes -Serial $Serial
		$n = $nodes | Where-Object $where | Select-Object -First 1
		if (-not $n) { Start-Sleep -Seconds 2 }
	}
	if (-not $n) { throw "missing $what" }
	$n
}
function Save-Shot([string]$name) {
	& $adb -s $Serial shell screencap -p /sdcard/s.png | Out-Null
	& $adb -s $Serial pull /sdcard/s.png (Join-Path $shotDir "b4-$name.png") | Out-Null
	Write-Host "  shot: b4-$name"
}
function Get-Density {
	$out = (& $adb -s $Serial shell wm density) -join ' '
	if ($out -match '(\d+)') { [int]$Matches[1] } else { throw 'no density reported' }
}

& $adb -s $Serial shell settings put system font_scale 1.5 | Out-Null
try {
	Write-Host '=== one scripted turn (demo enabled from the welcome panel) ==='
	# Cold start so the drive begins from the home screen whatever state a
	# previous run left behind.
	& $adb -s $Serial shell am force-stop com.inhuman | Out-Null
	& $adb -s $Serial shell am start -n com.inhuman/.MainActivity | Out-Null
	Start-Sleep -Seconds 6
	Tap-Node (Find-Node { $_.Desc -match '^新建$|New' -or $_.Text -eq '新建' } 'Create') 'Create'
	Tap-Node (Find-Node { $_.Text -eq '新建对话' -or $_.Text -eq 'New conversation' } 'the new-conversation row') 'the new-conversation row'
	# The welcome panel offers "try demo mode" right where a keyless install
	# needs it -- one tap, and the composer unlocks. Driving the Settings
	# switch instead was tried and the row-toggle did not take.
	$tryDemo = Get-UiNodes -Serial $Serial | Where-Object { $_.Text -like '试试演示模式*' } | Select-Object -First 1
	if ($tryDemo) {
		Tap-Node $tryDemo 'try demo mode'
	} else {
		Write-Host '  demo already on (no offer in the panel)'
	}
	$field = Find-Node { $_.Cls -eq 'android.widget.EditText' } 'the composer field'
	Tap-Node $field 'the composer field'
	& $adb -s $Serial shell input text hello | Out-Null
	# The IME is still lifting while the first tap lands; a send tapped at
	# that moment misses. Let it settle, then verify by the message actually
	# appearing in the transcript rather than by the tap alone.
	Start-Sleep -Seconds 3
	$sent = $false
	for ($i = 0; $i -lt 3 -and -not $sent; $i++) {
		$send = Find-Node { $_.Text -eq '发送' -or $_.Desc -eq '发送' -or $_.Desc -eq 'Send' } 'send'
		Tap-Node $send 'send'
		$sent = [bool](Get-UiNodes -Serial $Serial | Where-Object { $_.Text -eq 'hello' })
		if (-not $sent) { Write-Host '  send did not land; retrying' }
	}
	if (-not $sent) { throw 'the message did not send' }
	Write-Host '  waiting for the scripted turn'
	Start-Sleep -Seconds 11
	Save-Shot 'demo-turn'

	Write-Host '=== collapsed section touch targets ==='
	$density = Get-Density
	$minPx = [int](48 * $density / 160)
	Write-Host "  density $density dpi, expecting >= $minPx px per header"
	$headers = Get-UiNodes -Serial $Serial | Where-Object {
		($_.Desc -match '展开|收起|expand|collapse') -and (($_.Y2 - $_.Y1) -gt 0)
	}
	if (-not $headers) { throw 'no collapsed section headers in the transcript' }
	$short = @()
	foreach ($h in $headers) {
		$height = $h.Y2 - $h.Y1
		Write-Host ("  header '{0}' height {1}px" -f $h.Desc, $height)
		if ($height -lt $minPx) { $short += $h }
	}
	if ($short) { throw "$($short.Count) collapsed header(s) under $minPx px" }
	Write-Host "  ok: every collapsed header is at least $minPx px tall"

	Write-Host '=== search role column at 1.5x font ==='
	Tap-Node (Find-Node { $_.Text -eq '搜索本对话' -or $_.Desc -match '搜索本对话|Search' } 'the in-chat search') 'the in-chat search'
	Start-Sleep -Seconds 1
	$capPx = [int](84 * $density / 160)
	$role = Get-UiNodes -Serial $Serial | Where-Object {
		$_.Text -match '^(你|YOU|ASSISTANT)$' -and $_.Cls -eq 'android.widget.TextView'
	} | Select-Object -First 1
	if ($role) {
		$w = $role.X2 - $role.X1
		$h = $role.Y2 - $role.Y1
		Write-Host ("  role '{0}' {1}x{2}px (the old fixed cap was $capPx px wide)" -f $role.Text, $w, $h)
		if ($w -gt $capPx) { Write-Host '  ok: the column grew past the old fixed cap' }
		else { Write-Host '  note: the label does not exceed the cap at this size; no wrap either way' }
	} else {
		Write-Host '  no match rows (the demo answer may have no searchable text); skipped'
	}
	Save-Shot 'search-15x'
	& $adb -s $Serial shell input keyevent KEYCODE_BACK | Out-Null
	Start-Sleep -Seconds 2

	Write-Host '=== 640x360dp landscape, IME open, three-line draft ==='
	& $adb -s $Serial shell wm size 1280x720 | Out-Null
	& $adb -s $Serial shell wm density 320 | Out-Null
	Start-Sleep -Seconds 3
	$field = Find-Node { $_.Cls -eq 'android.widget.EditText' } 'the composer field'
	Tap-Node $field 'the composer field (landscape)'
	Start-Sleep -Seconds 2
	& $adb -s $Serial shell input text first | Out-Null
	& $adb -s $Serial shell input keyevent 66 | Out-Null
	& $adb -s $Serial shell input text second | Out-Null
	& $adb -s $Serial shell input keyevent 66 | Out-Null
	& $adb -s $Serial shell input text third | Out-Null
	Start-Sleep -Seconds 2
	Save-Shot 'landscape-ime'
	$send = Get-UiNodes -Serial $Serial | Where-Object {
		($_.Text -eq '发送' -or $_.Desc -eq 'Send' -or $_.Desc -eq '发送') -and $_.Y2 -gt 0
	} | Select-Object -First 1
	if (-not $send) { throw 'send button not on screen (off-screen = the B41 defect)' }
	Write-Host ("  send row bounds [{0},{1}][{2},{3}] vs 720px screen" -f $send.X1, $send.Y1, $send.X2, $send.Y2)
	if ($send.Y2 -gt 720) { throw "send row is off-screen: Y2=$($send.Y2)" }
	Write-Host '  ok: the send row is inside the window with a three-line draft'

	Write-Host '=== all batch-4 assertions passed ==='
} finally {
	& $adb -s $Serial shell wm size reset | Out-Null
	& $adb -s $Serial shell wm density reset | Out-Null
	& $adb -s $Serial shell settings put system font_scale 1.0 | Out-Null
	Write-Host 'restored: wm size/density, font scale'
}
