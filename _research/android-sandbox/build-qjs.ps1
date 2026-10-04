# Build libqjssandbox.so
#
# Reproduces out/libqjssandbox.so from jni/qjs_jni.c and a QuickJS checkout.
# Not part of the app build; run by hand.
#
# ## Getting the QuickJS source
#
# The checkout is not in this repository -- it is a third-party tree with its own
# `.git`, and vendoring it would put someone else's history inside ours. Obtain
# it, or let this script do it:
#
#     git clone https://github.com/bellard/quickjs.git <path>
#
# Measured against **VERSION 2026-06-04, commit 535a7c2**. A later QuickJS may
# need changes to jni/qjs_jni.c; the version is named because "latest" is not a
# reproducible input.
param(
	[string]$Abi = 'x86_64',
	[string]$Ndk = 'C:\android-sdk\ndk\28.2.13676358',
	# Beside the repository rather than inside it, so a tool that walks the tree
	# does not walk into a second project. Override for a checkout elsewhere.
	[string]$QuickJs = "$PSScriptRoot\..\..\.sandbox-build\quickjs",
	# Clones QuickJS into -QuickJs when it is not already there.
	[switch]$Fetch
)

$ErrorActionPreference = 'Stop'

# One target triple per ABI. API 34 matches the app's own minSdk, so nothing is
# compiled against a platform older than the app supports.
$targets = @{
	'x86_64'    = 'x86_64-linux-android34'
	'arm64-v8a' = 'aarch64-linux-android34'
	'armeabi-v7a' = 'armv7a-linux-androideabi34'
}
if (-not $targets.ContainsKey($Abi)) {
	throw "unknown ABI '$Abi'; expected one of $($targets.Keys -join ', ')"
}

$tool = Join-Path $Ndk 'toolchains\llvm\prebuilt\windows-x86_64'
$clang = Join-Path $tool 'bin\clang.exe'
if (-not (Test-Path $clang)) { throw "clang not found at $clang" }

# Fetch on request, so a fresh clone of this repository can build the artifact
# without anyone having to read the header to find out where the source lives.
if ($Fetch -and -not (Test-Path (Join-Path $QuickJs 'quickjs.c'))) {
	$parent = Split-Path $QuickJs -Parent
	New-Item -ItemType Directory -Force -Path $parent | Out-Null
	Write-Host "cloning QuickJS into $QuickJs"
	git clone --quiet https://github.com/bellard/quickjs.git $QuickJs
	if ($LASTEXITCODE -ne 0) { throw "git clone failed" }
}
if (-not (Test-Path (Join-Path $QuickJs 'quickjs.c'))) {
	throw "QuickJS not found at $QuickJs -- pass -QuickJs <path>, or -Fetch to clone it"
}

$here = $PSScriptRoot
$jni = Join-Path $here 'jni'
$outDir = Join-Path $here 'out'
New-Item -ItemType Directory -Force -Path $outDir | Out-Null

# The version macro lives in the Makefile, not in a header.
$version = (Get-Content (Join-Path $QuickJs 'VERSION') -Raw).Trim()

$sources = @(
	(Join-Path $jni 'qjs_jni.c'),
	(Join-Path $QuickJs 'quickjs.c'),
	(Join-Path $QuickJs 'libregexp.c'),
	(Join-Path $QuickJs 'libunicode.c'),
	(Join-Path $QuickJs 'cutils.c'),
	(Join-Path $QuickJs 'dtoa.c')
)
foreach ($s in $sources) { if (-not (Test-Path $s)) { throw "missing source: $s" } }

$outFile = Join-Path $outDir "libqjssandbox.so"
$clangArgs = @(
	"--target=$($targets[$Abi])",
	"--sysroot=$(Join-Path $tool 'sysroot')",
	'-shared', '-fPIC', '-O2',
	'-DANDROID', '-D_GNU_SOURCE', "-DCONFIG_VERSION=`"$version`"",
	'-I', $QuickJs,
	'-o', $outFile
) + $sources + @('-lm', '-llog', '-ldl')

Write-Host "building $Abi (QuickJS $version) ..."
& $clang @clangArgs
if ($LASTEXITCODE -ne 0) { throw "clang failed with $LASTEXITCODE" }

# Kept per ABI rather than overwritten: both are wanted eventually, and a build
# that silently replaces the other one's output is a trap.
$perAbi = Join-Path $outDir "libqjssandbox-$Abi.so"
Move-Item $outFile $perAbi -Force

$size = [math]::Round((Get-Item $perAbi).Length / 1KB)
Write-Host "wrote $perAbi ($size KB)"
