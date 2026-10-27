<#
The verification gate, in one command.

    .\tools\ci.ps1
    .\tools\ci.ps1 -SkipDesktop
    .\tools\ci.ps1 -LogPath C:\temp\inhuman-ci.log

This is what a CI host should call. It deliberately installs and downloads
nothing: the JDK, the Android SDK and the Gradle distribution are properties of
the machine (README "Another machine"), and a script that guesses at them is a
second source of truth. `tools/setup-machine.ps1` is invoked first so a fresh
clone fails with "platform 37 is missing" instead of a Gradle stack trace, and
so the wrapper's checked-in `file:` URL is repaired on a machine that does not
have `C:\android-toolchain`.

## Why --rerun-tasks

With a warm build cache a plain run can report BUILD SUCCESSFUL without
compiling the file that was just changed. The gate is only worth having if it
actually compiled the change.

## Why warnings fail the build

This tree compiles clean today (0 warnings, 0 errors). That is a property worth
keeping, and a warning nobody reads is how it stops being true. Any compiler
warning -- `w:` from Kotlin, `warning:` from javac -- fails the run after Gradle
finishes, with the log kept for the report.

The exit code is the build's exit code when the build fails, otherwise 1 when
the build succeeded but the warning scan did not. A caller only has to test for
non-zero.
#>
[CmdletBinding()]
param(
	[switch]$SkipDesktop,
	[string]$LogPath
)

$ErrorActionPreference = 'Stop'

$repo = Split-Path -Parent $PSScriptRoot
Push-Location $repo
try {
	if (-not $LogPath) { $LogPath = Join-Path $repo 'build\ci.log' }
	$logDir = Split-Path -Parent $LogPath
	if (-not (Test-Path $logDir)) {
		New-Item -ItemType Directory -Path $logDir -Force | Out-Null
	}

	Write-Host '== prerequisites ========================================='
	& (Join-Path $PSScriptRoot 'setup-machine.ps1')
	# `$?` rather than `$LASTEXITCODE`: setup-machine has no native command on a
	# machine without JAVA_HOME, so its exit code would be whatever ran before.
	if (-not $?) {
		Write-Host 'ci: prerequisites are not met; see the FAIL lines above.' -ForegroundColor Red
		exit 1
	}

	$tasks = @(
		':app:assembleDebug'
		':app:testDebugUnitTest'
		':core:data:testDebugUnitTest'
		':core:db:test'
		':core:engine:test'
		':core:ai:test'
		':core:files:test'
		':core:common:test'
	)
	if (-not $SkipDesktop) { $tasks += ':desktopApp:test' }

	$gradle = Join-Path $repo 'gradlew.bat'
	if (-not (Test-Path $gradle)) {
		Write-Host "ci: $gradle is missing" -ForegroundColor Red
		exit 1
	}

	Write-Host ''
	Write-Host '== verification =========================================='
	Write-Host ('   ' + ($tasks -join ' '))
	Write-Host "   log: $LogPath"
	Write-Host ''

	$started = Get-Date
	& $gradle @tasks '--console=plain' '--rerun-tasks' 2>&1 |
		Tee-Object -FilePath $LogPath
	$exit = $LASTEXITCODE
	$elapsed = (Get-Date) - $started

	Write-Host ''
	if ($exit -ne 0) {
		Write-Host ("ci: FAILED -- gradle exited $exit after {0:n1}s; log: $LogPath" -f $elapsed.TotalSeconds) -ForegroundColor Red
		exit $exit
	}

	$warnings = @(Select-String -LiteralPath $LogPath -Pattern '^(w: |warning: )' -CaseSensitive)
	if ($warnings.Count -gt 0) {
		Write-Host ("ci: FAILED -- build succeeded but produced {0} compiler warning(s) after {1:n1}s:" -f $warnings.Count, $elapsed.TotalSeconds) -ForegroundColor Red
		$warnings | Select-Object -First 20 | ForEach-Object { Write-Host ("   " + $_.Line) -ForegroundColor Red }
		if ($warnings.Count -gt 20) { Write-Host ("   ... and {0} more in {1}" -f ($warnings.Count - 20), $LogPath) -ForegroundColor Red }
		exit 1
	}

	if (-not (Select-String -LiteralPath $LogPath -Pattern '^BUILD SUCCESSFUL' -Quiet)) {
		Write-Host "ci: FAILED -- no BUILD SUCCESSFUL line in $LogPath" -ForegroundColor Red
		exit 1
	}

	Write-Host ("ci: OK -- {0} task(s), no warnings, {1:n1}s" -f $tasks.Count, $elapsed.TotalSeconds) -ForegroundColor Green
} finally {
	Pop-Location
}