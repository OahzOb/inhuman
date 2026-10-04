# Read a uiautomator dump without lying about quoted text.
#
# The previous tools in this repository parsed `text="..."` with a double quote
# in the pattern. uiautomator switches to single quotes when a value contains a
# double quote -- which is exactly what the delete confirmation's title does,
# because it embeds the conversation name:
#
#     text='Delete "Reply with exactly the word banana…"?'
#
# So every earlier reading of that title returned nothing, and "the title did not
# change" was reported three times on the strength of a value that was never
# read. The lesson is narrow and worth keeping: parse the attribute, not a quote
# character, and when a value looks empty, check whether it was found at all.
#
# Load with `Import-Module`. Dot-sourcing a .psm1 runs it but does not put its
# functions in the caller's scope, which fails as "not recognized as a cmdlet"
# at the first call rather than at the import.
$ErrorActionPreference = 'Stop'
$script:adb = 'C:\android-sdk\platform-tools\adb.exe'
$adb = 'C:\android-sdk\platform-tools\adb.exe'

function Get-UiNodes {
    param([string]$Serial = 'emulator-5554')
    $stamp = Get-Date -Format 'HHmmssfff'
    & $script:adb -s $Serial shell "rm -f /sdcard/pu*.xml" 2>&1 | Out-Null
    & $script:adb -s $Serial shell "uiautomator dump /sdcard/pu$stamp.xml" 2>&1 | Out-Null
    $local = Join-Path $env:TEMP "pu-$stamp.xml"
    & $script:adb -s $Serial pull "/sdcard/pu$stamp.xml" $local 2>&1 | Out-Null
    if (-not (Test-Path $local)) { return @() }

    $xml = Get-Content $local -Raw
    $out = @()
    foreach ($tag in [regex]::Matches($xml, '<node\b[^>]*?(?:/>|>)')) {
        $t = $tag.Value
        $b = [regex]::Match($t, 'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
        if (-not $b.Success) { continue }
        $out += [pscustomobject]@{
            # Quote-agnostic: the value is whatever sits between the opening
            # quote character and the same one before the next attribute.
            Text = (Get-Attr $t 'text')
            Desc = (Get-Attr $t 'content-desc')
            Cls  = (Get-Attr $t 'class')
            Clickable = (Get-Attr $t 'clickable') -eq 'true'
            X1 = [int]$b.Groups[1].Value; Y1 = [int]$b.Groups[2].Value
            X2 = [int]$b.Groups[3].Value; Y2 = [int]$b.Groups[4].Value
        }
    }
    return $out
}

function Get-Attr([string]$tag, [string]$name) {
    # Matches name="..." or name='...', and unescapes the entities uiautomator
    # writes into attributes.
    $m = [regex]::Match($tag, "\b$name=(?:`"([^`"]*)`"|'([^']*)')")
    if (-not $m.Success) { return '' }
    $v = if ($m.Groups[1].Success) { $m.Groups[1].Value } else { $m.Groups[2].Value }
    return $v.Replace('&quot;', '"').Replace('&apos;', "'").Replace('&amp;', '&')
}

function Show-State {
    param([string]$Serial, [string]$Label)
    $n = Get-UiNodes -Serial $Serial
    $title = ($n | Where-Object { $_.Text -like 'Delete *' } | Select-Object -First 1).Text
    $body = ($n | Where-Object {
        $_.Text -like 'This deletes*' -or $_.Text -like 'This cannot be undone*'
    } | ForEach-Object { $_.Text }) -join ' | '
    $dialogOpen = ($n | Where-Object { $_.Text -eq 'Cancel' }).Count -gt 0
    $inChat = ($n | Where-Object { $_.Desc -eq 'Attach an image' }).Count -gt 0
    $rows = ($n | Where-Object { $_.Desc -eq 'Delete conversation' }).Count

    Write-Host "  [$Label]"
    Write-Host "    dialog open : $dialogOpen"
    Write-Host "    title       : $(if ($title) { $title } else { '(none)' })"
    Write-Host "    body        : $(if ($body) { $body } else { '(none)' })"
    Write-Host "    in a chat   : $inChat"
    Write-Host "    list rows   : $rows"
    return [pscustomobject]@{ Title = $title; Body = $body; Dialog = $dialogOpen; InChat = $inChat; Rows = $rows }
}
