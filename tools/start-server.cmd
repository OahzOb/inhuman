@echo off
setlocal
cd /d "%~dp0.."
where python >nul 2>nul
if errorlevel 1 (
	echo Python was not found on PATH.
	echo Install Python 3, or run "python tools\dev-server.py" with the right interpreter.
	pause
	exit /b 1
)

echo Starting the inhuman update/log server on port 8787.
echo Keep this window open; closing it or Ctrl+C stops the server.
echo.
python tools\dev-server.py --port 8787
if errorlevel 1 (
	echo.
	echo The server exited with an error. Is port 8787 already in use?
	pause
)
