# Helper: fetch a URL and strip HTML to plain text. Usage:
#   . C:\Projects\llm_app\_research_tools\fetch.ps1
#   Get-Text "https://..."            -> full plain text (whitespace-collapsed)
#   Get-Text "https://..." "keyword"  -> +-chars around first occurrence of keyword
$ProgressPreference = 'SilentlyContinue'

function Get-Raw([string]$Url) {
    if ($Url -match 'googlesource\.com' -and $Url -notmatch 'format=TEXT') {
        if ($Url -match '\?') { $Url = $Url + '&format=TEXT' } else { $Url = $Url + '?format=TEXT' }
    }
    $r = Invoke-WebRequest -Uri $Url -UseBasicParsing -TimeoutSec 90 -Headers @{ 'User-Agent' = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64)' }
    $c = $r.Content
    if ($c -is [byte[]]) { $c = [System.Text.Encoding]::UTF8.GetString($c) }
    if ($Url -match 'format=TEXT') {
        try { $c = [System.Text.Encoding]::UTF8.GetString([Convert]::FromBase64String(($c -replace '\s', ''))) } catch { }
    }
    return $c
}

function Get-Text([string]$Url, [string]$Keyword = '', [int]$Pad = 2500) {
    $c = Get-Raw $Url
    $t = $c -replace '(?s)<script.*?</script>', ' ' `
        -replace '(?s)<style.*?</style>', ' ' `
        -replace '(?s)<!--.*?-->', ' ' `
        -replace '(?s)<head.*?</head>', ' ' `
        -replace '(?s)<nav.*?</nav>', ' ' `
        -replace '<[^>]+>', ' ' `
        -replace '&nbsp;', ' ' -replace '&amp;', '&' -replace '&lt;', '<' -replace '&gt;', '>' `
        -replace '&quot;', '"' -replace '&#39;', "'" -replace '&hellip;', '...'
    $t = ($t -replace '\s+', ' ').Trim()
    if ($Keyword -eq '') { return $t }
    $i = $t.IndexOf($Keyword, [System.StringComparison]::OrdinalIgnoreCase)
    if ($i -lt 0) { return "KEYWORD NOT FOUND: $Keyword`n--- first 1500 chars ---`n" + $t.Substring(0, [Math]::Min(1500, $t.Length)) }
    $s = [Math]::Max(0, $i - $Pad)
    $len = [Math]::Min($t.Length - $s, ($Pad * 2) + $Keyword.Length)
    return $t.Substring($s, $len)
}

# Print every line of a text blob matching a regex, with a bit of context
function Grep-Text([string]$Text, [string]$Pattern, [int]$Context = 0) {
    $lines = $Text -split "`n"
    for ($i = 0; $i -lt $lines.Count; $i++) {
        if ($lines[$i] -match $Pattern) {
            $a = [Math]::Max(0, $i - $Context); $b = [Math]::Min($lines.Count - 1, $i + $Context)
            for ($j = $a; $j -le $b; $j++) { "{0,5}: {1}" -f ($j + 1), $lines[$j] }
            "-----"
        }
    }
}
