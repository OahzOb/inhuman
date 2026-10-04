# Does regenerating survive what the turn actually did?
#
# Two conversations, asked for the same shape of answer, differing only in
# whether the turn touched the workspace. The control must be present for one
# and absent, with a reason, for the other.
param([string]$Serial = 'emulator-5556', [string]$Label = 'emu')
$ErrorActionPreference = 'Stop'
$adb = 'C:\android-sdk\platform-tools\adb.exe'
$shotDir = 'C:\Projects\inhuman\.shots'
New-Item -ItemType Directory -Force -Path $shotDir | Out-Null
Add-Type -AssemblyName System.Drawing

function Get-Nodes {
    # Unique name and a delete first: `uiautomator dump` refuses to overwrite and
    # still reports success, so a reused path returns the first dump forever.
    $stamp = Get-Date -Format 'HHmmssfff'
    & $adb -s $Serial shell "rm -f /sdcard/ui*.xml" 2>&1 | Out-Null
    & $adb -s $Serial shell "uiautomator dump /sdcard/ui$stamp.xml" 2>&1 | Out-Null
    $local = Join-Path $env:TEMP "ui-$stamp.xml"
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
    & $adb -s $Serial shell input tap $x $y | Out-Null
    Start-Sleep -Seconds 2
}
function Tap-Find([string]$p, [string]$what) {
    $n = Get-Nodes | Where-Object { ($_.Desc -match $p) -or ($_.Text -match $p) } | Select-Object -First 1
    if (-not $n) { Write-Host "  [$what] missing:"; Labels | ForEach-Object { Write-Host "    '$_'" }; throw "missing $what" }
    Tap-Node $n $what
}
function Save-Shot([string]$n) {
    $png = Join-Path $shotDir "$Label-$n.png"
    & $adb -s $Serial shell screencap -p /sdcard/s.png | Out-Null
    & $adb -s $Serial pull /sdcard/s.png $png 2>&1 | Out-Null
    $img = [System.Drawing.Image]::FromFile($png)
    $tw = 420
    $b = New-Object System.Drawing.Bitmap($tw, [int]($img.Height * ($tw / [double]$img.Width)))
    $g = [System.Drawing.Graphics]::FromImage($b)
    $g.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
    $g.DrawImage($img, 0, 0, $b.Width, $b.Height); $g.Dispose(); $img.Dispose()
    $b.Save((Join-Path $shotDir "$Label-$n.small.png"), [System.Drawing.Imaging.ImageFormat]::Png); $b.Dispose()
    Write-Host "  shot: $Label-$n"
}
function Type-Text([string]$t) { & $adb -s $Serial shell input text ($t -replace ' ', '%s') | Out-Null; Start-Sleep -Seconds 1 }
function Ask([string]$question, [int]$waitSeconds, [string]$shot) {
    Tap-Find '^Message$' 'the composer'
    Type-Text $question
    & $adb -s $Serial shell input keyevent KEYCODE_ESCAPE | Out-Null
    Start-Sleep -Seconds 2
    Tap-Find '^Send$' 'Send'
    Write-Host "  waiting ${waitSeconds}s ..."
    Start-Sleep -Seconds $waitSeconds
    Save-Shot $shot
    Labels
}
function NewConversation {
    Tap-Find '^New conversation$' 'New conversation'
    Start-Sleep -Seconds 3
    Tap-Find '^Assistant$' 'the Assistant'
    Start-Sleep -Seconds 3
}

Write-Host '=== a purely conversational turn ==='
& $adb -s $Serial shell am force-stop com.inhuman | Out-Null
& $adb -s $Serial shell am start -n 'com.inhuman/.MainActivity' | Out-Null
Start-Sleep -Seconds 5
NewConversation
$text = Ask 'Say hello in exactly three words.' 22 'regen-text'
Write-Host '  on screen:'
$text | ForEach-Object { Write-Host "    '$_'" }
$hasRegen = ($text | Where-Object { $_ -match '^(Regenerate|Answer again)$' }) -ne $null
Write-Host ''
Write-Host "  ==> regenerate offered for a text reply: $hasRegen"
if (-not $hasRegen) { throw 'a text reply should be regenerable' }

Write-Host ''
Write-Host '=== a turn that writes a file ==='
Tap-Find '^‹$' 'back to the list'
Start-Sleep -Seconds 2
NewConversation
$write = Ask 'Create a file called zz-regen-test.txt containing the word hello. Then say done.' 30 'regen-write'
Write-Host '  on screen:'
$write | ForEach-Object { Write-Host "    '$_'" }
$hasRegen2 = ($write | Where-Object { $_ -match '^(Regenerate|Answer again)$' }) -ne $null
$hasReason = ($write | Where-Object { $_ -match 'changed files.*cannot be regenerated' }) -ne $null
Write-Host ''
Write-Host "  ==> regenerate offered after writing a file: $hasRegen2   (must be False)"
Write-Host "  ==> reason shown: $hasReason   (must be True)"

if ($hasRegen2) { throw 'a reply that wrote a file must not be regenerable' }
if (-not $hasReason) { throw 'the reason for withholding regeneration was not shown' }
Write-Host ''
Write-Host '  BOTH CHECKS PASSED'
