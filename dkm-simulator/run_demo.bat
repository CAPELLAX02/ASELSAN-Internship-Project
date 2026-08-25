@echo off
setlocal

rem End-to-end demo: builds bin_gen/c_sim/mock_r, generates a message binary,
rem starts c_sim listening on the RSP/RSM/CRM ports, then starts mock_r so it
rem connects to c_sim. c_sim must already be listening before mock_r starts --
rem mock_r's connect() only tries once and gives up on failure.
rem
rem Usage: run_demo.bat [speed]
rem   speed   c_sim's playback speed multiplier, default 2.0

set "ROOT=%~dp0"
set "SPEED=%~1"
if "%SPEED%"=="" set "SPEED=2.0"

set "BIN_GEN_EXE=%ROOT%bin_gen\build\bin_gen.exe"
set "SIM_EXE=%ROOT%c_sim\build\c_sim.exe"
set "MOCK_R_EXE=%ROOT%mock_r\build\mock_r.exe"
set "RUN_DIR=%ROOT%run"
set "INPUT_BIN=%RUN_DIR%\input.bin"

echo === rsim end-to-end demo (speed=%SPEED%x) ===

for %%P in (bin_gen c_sim mock_r) do (
    if not exist "%ROOT%%%P\build" (
        echo.
        echo "%ROOT%%%P\build" doesn't exist yet -- configure it once first, e.g.:
        echo   cmake -S "%ROOT%%%P" -B "%ROOT%%%P\build" -G Ninja
        exit /b 1
    )
)

echo.
echo Building bin_gen...
cmake --build "%ROOT%bin_gen\build" || exit /b 1

echo Building c_sim...
cmake --build "%ROOT%c_sim\build" || exit /b 1

echo Building mock_r...
cmake --build "%ROOT%mock_r\build" || exit /b 1

if not exist "%RUN_DIR%" mkdir "%RUN_DIR%"

echo.
echo Generating message binary at "%INPUT_BIN%"...
"%BIN_GEN_EXE%" "%INPUT_BIN%" || exit /b 1

echo.
echo Starting c_sim (listener) in a new window...
start "c_sim" cmd /k ""%SIM_EXE%" "%INPUT_BIN%" %SPEED%"

echo Waiting a couple seconds for c_sim to start listening before mock_r connects...
rem ping-based delay instead of `timeout` -- timeout errors out when stdin
rem isn't a real console (e.g. launched from Task Scheduler or another script)
ping -n 3 127.0.0.1 >nul

echo Starting mock_r in a new window...
start "mock_r" cmd /k ""%MOCK_R_EXE%""

echo.
echo Both are running in their own windows -- watch "c_sim" for the live send/receive log.
echo Close a window (or Ctrl+C inside it) to stop that process.

endlocal
