# Establish the real wire format for images, and what happens when the model
# cannot see them.
#
# The point is not that one format works -- it is which format, and what the
# failure looks like, because the app has to decide whether to offer an image
# attachment at all and what to say when it is refused.
$key = (Get-Content 'C:\android-toolchain\.deepseek-key' -Raw).Trim()
# 1x1 red PNG.
$b64 = 'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg=='

function Try-Call([string]$name, [string]$body) {
    Write-Host "=== $name ==="
    $bytes = [System.Text.Encoding]::UTF8.GetBytes($body)
    $req = [System.Net.HttpWebRequest]::Create('https://api.deepseek.com/chat/completions')
    $req.Method = 'POST'
    $req.ContentType = 'application/json'
    $req.Headers.Add('Authorization', "Bearer $key")
    $req.Timeout = 90000
    $req.ContentLength = $bytes.Length
    $s = $req.GetRequestStream(); $s.Write($bytes, 0, $bytes.Length); $s.Close()
    try {
        $resp = $req.GetResponse()
        $reader = New-Object System.IO.StreamReader($resp.GetResponseStream())
        $json = $reader.ReadToEnd() | ConvertFrom-Json
        Write-Host "  HTTP 200"
        Write-Host "  content: '$($json.choices[0].message.content)'"
        if ($json.choices[0].message.reasoning_content) {
            $rc = $json.choices[0].message.reasoning_content
            Write-Host "  reasoning: '$($rc.Substring(0, [Math]::Min(140, $rc.Length)))'"
        }
    } catch [System.Net.WebException] {
        $r = $_.Exception.Response
        $code = if ($r) { [int]$r.StatusCode } else { '?' }
        $reader = New-Object System.IO.StreamReader($r.GetResponseStream())
        $err = $reader.ReadToEnd()
        Write-Host "  HTTP $code"
        Write-Host "  $($err.Substring(0, [Math]::Min(260, $err.Length)))"
    }
    Write-Host ''
}

Try-Call 'A: content parts, image_url as an object' @"
{"model":"deepseek-flash","max_tokens":60,"messages":[{"role":"user","content":[{"type":"text","text":"What colour is this image? One word."},{"type":"image_url","image_url":{"url":"data:image/png;base64,$b64"}}]}]}
"@

Try-Call 'B: content parts, image_url as a bare string' @"
{"model":"deepseek-flash","max_tokens":60,"messages":[{"role":"user","content":[{"type":"text","text":"What colour is this image? One word."},{"type":"image_url","image_url":"data:image/png;base64,$b64"}]}]}
"@

Try-Call 'C: text-only model (deepseek-v4-pro) given an image' @"
{"model":"deepseek-v4-pro","max_tokens":60,"messages":[{"role":"user","content":[{"type":"text","text":"What colour is this image? One word."},{"type":"image_url","image_url":{"url":"data:image/png;base64,$b64"}}]}]}
"@

Try-Call 'D: plain text on the vision model, parts format' @"
{"model":"deepseek-flash","max_tokens":30,"messages":[{"role":"user","content":[{"type":"text","text":"Say ok."}]}]}
"@
