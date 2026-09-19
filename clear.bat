@echo off
setlocal

REM Define the target file path relative to the project root
set "TARGET_FILE=app\src\main\java\io\github\zensu357\camswap\NotificationService.java"

REM Check if file exists
if not exist "%TARGET_FILE%" (
    echo [ERROR] File not found: %TARGET_FILE%
    echo Please run this script from the root directory of the CamSwap project.
    pause
    exit /b 1
)

REM Create a backup just in case
echo [INFO] Creating backup: %TARGET_FILE%.bak
copy /y "%TARGET_FILE%" "%TARGET_FILE%.bak" >nul

echo [INFO] Removing "Previous" and "Exit" buttons from Notification...

REM Use PowerShell for robust multi-line regex replacement
powershell -NoProfile -ExecutionPolicy Bypass -Command ^
    "$f = '%TARGET_FILE%'; " ^
    "$c = Get-Content -Path $f -Raw; " ^
    "$c = $c -replace '(?s)builder\.addAction\(new Notification\.Action\.Builder\(null, getString\(R\.string\.notif_action_prev\),\s*getPendingIntent\(ACTION_PREV_INTERNAL\)\)\.build\(\)\);\s*', ''; " ^
    "$c = $c -replace '(?s)builder\.addAction\(new Notification\.Action\.Builder\(null, getString\(R\.string\.notif_action_exit\),\s*getPendingIntent\(ACTION_EXIT_INTERNAL\)\)\.build\(\)\);\s*', ''; " ^
    "Set-Content -Path $f -Value $c -NoNewline;"

echo.
echo [SUCCESS] Notification UI updated successfully!
echo [INFO] The notification will now only show: Next | Rotate | Mode
echo.
pause