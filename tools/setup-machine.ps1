<#
What a fresh clone needs on a new Windows machine, checked rather than assumed.

Run it after `git clone`:

    .\tools\setup-machine.ps1

It prints one line per prerequisite and exits non-zero if the build cannot run,
so a failed first build is explained before it happens rather than after.

## Why the wrapper needs repairing at all

`gradle/wrapper/gradle-wrapper.properties` is checked in pointing at a `file:`
URL inside `C:\android-toolchain`, because the JVM on the machine this repo was
written on cannot reach `services.gradle.org` through its proxy (PowerShell can;
the JVM cannot). That is a property of *that* machine, and a clone anywhere else
inherits a URL to a file that does not exist -- `gradlew` then fails at the first
unzip.

So when the checked-in `file:` target is missing, this script points the wrapper
back at the official distribution for the same Gradle version (-NoRepair skips
that and only reports). Nothing else in the repository is rewritten.

## What it does not do

It installs nothing and downloads nothing. Two of the three prerequisites are
SDK installs that need a licence click, and guessing paths is how a script
becomes a second source of truth.
#>
param(
	[switch]$NoRepair
)

$ErrorActionPreference = "Stop"
$repo = Split-Path -Parent $PSScriptRoot
Set-Location $repo

$ok = New-Object System.Collections.Generic.List[string]
$broken = New-Object System.Collections.Generic.List[string]

function Pass([string]$line) { $ok.Add($line) }
function Fail([string]$line, [string]$fix) { $broken.Add("$line`n    fix: $fix") }

# --- Gradle wrapper --------------------------------------------------------

$wrapperFile = Join-Path $repo "gradle\wrapper\gradle-wrapper.properties"
$distLine = Select-String -LiteralPath $wrapperFile -Pattern '^distributionUrl=(.*)$' |
	Select-Object -First 1
if (-not $distLine) {
	Fail "gradle-wrapper.properties has no distributionUrl" "restore gradle/wrapper/gradle-wrapper.properties from git"
} else {
	# The properties file escapes the colon and backslashes: file\:/C\:/x.zip
	$raw = $distLine.Matches[0].Groups[1].Value.Trim()
	$url = $raw.Replace('\:', ':').Replace('\\', '\')
	$version = [regex]::Match($url, 'gradle-([0-9][^/\\]*?)-(bin|all)').Groups[1].Value
	if ($url -match '^file:') {
		$path = $url -replace '^file:/+', ''
		if (Test-Path $path) {
			Pass "gradle wrapper: local distribution present ($path)"
		} elseif (-not $version) {
			Fail "gradle wrapper: local distribution missing and the version is unreadable" "set distributionUrl by hand"
		} elseif ($NoRepair) {
			Fail "gradle wrapper: points at a file this machine does not have ($path)" "-NoRepair was given; drop it to rewrite the URL"
		} else {
			$official = "https\://services.gradle.org/distributions/gradle-$version-bin.zip"
			$text = [System.IO.File]::ReadAllText($wrapperFile)
			$text = $text.Replace($raw, $official)
			[System.IO.File]::WriteAllText($wrapperFile, $text)
			Pass "gradle wrapper: repaired for this machine -> $version-bin.zip (was a local file)"
			Pass "  the first .\gradlew.bat run downloads Gradle once"
		}
	} else {
		Pass "gradle wrapper: $url"
	}
}

# --- JDK -------------------------------------------------------------------

$javaHome = $env:JAVA_HOME
$java = if ($javaHome) { Join-Path $javaHome "bin\java.exe" } else { $null }
if (-not $java -or -not (Test-Path $java)) {
	Fail "JAVA_HOME is not set to a JDK" "install Temurin 17 and set JAVA_HOME (17 is what this repo builds with)"
} else {
	$versionLine = (& $java -version 2>&1 | Select-Object -First 1).ToString()
	$major = [regex]::Match($versionLine, '"(\d+)').Groups[1].Value
	if (-not $major) {
		Pass "java: $versionLine ($javaHome)"
	} elseif ([int]$major -lt 17) {
		Fail "java $major is too old" "the Android modules target Java 17; point JAVA_HOME at a 17+ JDK"
	} else {
		Pass "java $major ($javaHome)"
	}
}

# --- Android SDK -----------------------------------------------------------

$sdk = $env:ANDROID_HOME
if (-not $sdk) { $sdk = $env:ANDROID_SDK_ROOT }
if (-not $sdk) {
	$localProps = Join-Path $repo "local.properties"
	if (Test-Path $localProps) {
		$sdkLine = Select-String -LiteralPath $localProps -Pattern '^sdk\.dir=(.*)$' | Select-Object -First 1
		if ($sdkLine) { $sdk = $sdkLine.Matches[0].Groups[1].Value.Trim() }
	}
}
if (-not $sdk -or -not (Test-Path $sdk)) {
	Fail "no Android SDK found" "set ANDROID_HOME, or put sdk.dir in local.properties (that file is per-machine and gitignored)"
} else {
	$compileSdk = [regex]::Match(
		(Get-Content (Join-Path $repo "app\build.gradle") -Raw), 'compileSdk\s*=\s*(\d+)'
	).Groups[1].Value
	$platform = Get-ChildItem (Join-Path $sdk "platforms") -Directory -ErrorAction SilentlyContinue |
		Where-Object { $_.Name -eq "android-$compileSdk" -or $_.Name -like "android-$compileSdk.*" } |
		Select-Object -First 1
	if (-not $platform) {
		Fail "Android platform $compileSdk is not installed in $sdk" "sdkmanager `"platforms;android-$compileSdk`" (AGP 9 needs this exact platform, not the default one)"
	} else {
		Pass "android platform: $($platform.Name)"
	}
	$adb = Join-Path $sdk "platform-tools\adb.exe"
	if (Test-Path $adb) { Pass "platform-tools: adb present" } else { Fail "platform-tools missing" "sdkmanager platform-tools (needed to install on a device, not to build)" }
	$buildTools = Get-ChildItem (Join-Path $sdk "build-tools") -Directory -ErrorAction SilentlyContinue
	if ($buildTools) { Pass "build-tools: $(($buildTools | Sort-Object Name | Select-Object -Last 1).Name)" } else { Fail "no build-tools" "sdkmanager `"build-tools;36.0.0`"" }
}

# --- Optional: desktop host ------------------------------------------------

$desktopJava = $env:INHUMAN_DESKTOP_JAVA_HOME
if ($desktopJava) {
	Pass "desktop: INHUMAN_DESKTOP_JAVA_HOME=$desktopJava"
} else {
	Pass "desktop: not configured (only needed for :desktopApp:run / packageMsi; set INHUMAN_DESKTOP_JAVA_HOME to a full JDK 21+)"
}
$wix = $env:WIX_PATH
if ($wix) { Pass "desktop packaging: WIX_PATH=$wix" } else { Pass "desktop packaging: WIX_PATH unset (only needed for packageMsi)" }

# --- Report ----------------------------------------------------------------

Write-Host ""
foreach ($line in $ok) { Write-Host "  ok   $line" }
foreach ($line in $broken) { Write-Host "  FAIL $line" -ForegroundColor Red }
Write-Host ""

if ($broken.Count -gt 0) {
	Write-Host "$($broken.Count) prerequisite(s) missing. Fix those, then run it again." -ForegroundColor Red
	exit 1
}

Write-Host "Ready. First build:"
Write-Host "  .\gradlew.bat :app:assembleDebug"
Write-Host "Tests:"
Write-Host "  .\gradlew.bat :app:testDebugUnitTest :core:ai:test :core:db:test :core:engine:test :core:files:test :core:common:test :core:data:testDebugUnitTest --rerun-tasks"
Write-Host ""