param(
    [Parameter(Mandatory = $true)]
    [string]$Msi,

    # Either a PFX (with -Password) or a certificate thumbprint in CurrentUser\My.
    [string]$Pfx,
    [string]$Password,
    [string]$Thumbprint,

    # A timestamp keeps the signature valid after the certificate expires. It
    # needs a reachable RFC 3161 server; leave empty for an offline build.
    [string]$TimestampUrl = ''
)

$ErrorActionPreference = 'Stop'

if (-not (Test-Path -LiteralPath $Msi)) {
    throw "MSI not found: $Msi"
}
if (-not $Pfx -and -not $Thumbprint) {
    throw 'Give -Pfx (with -Password) or -Thumbprint.'
}

$signtool = Get-ChildItem 'C:\Program Files (x86)\Windows Kits\10\bin' `
    -Recurse -Filter 'signtool.exe' -ErrorAction SilentlyContinue |
    Where-Object { $_.FullName -match '\\x64\\' } |
    Sort-Object FullName -Descending |
    Select-Object -First 1
if (-not $signtool) {
    throw 'signtool.exe (x64) was not found in the Windows SDK.'
}

$arguments = @('sign', '/fd', 'SHA256')
if ($Pfx) {
    $arguments += @('/f', $Pfx)
    if ($Password) {
        $arguments += @('/p', $Password)
    }
} else {
    $arguments += @('/sha1', $Thumbprint)
}
if ($TimestampUrl) {
    $arguments += @('/tr', $TimestampUrl, '/td', 'SHA256')
}
$arguments += $Msi

& $signtool.FullName @arguments
if ($LASTEXITCODE -ne 0) {
    throw "signtool failed with exit code $LASTEXITCODE"
}

$signature = Get-AuthenticodeSignature -LiteralPath $Msi
"Status: $($signature.Status)"
"Signer: $($signature.SignerCertificate.Subject)"
if ($signature.Status -ne 'Valid') {
    # A self-signed certificate is a valid signature with an untrusted chain;
    # that is a certificate problem, not a signing failure.
    "The signature exists but the chain is not trusted on this machine."
}
