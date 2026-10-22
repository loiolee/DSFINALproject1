@echo off
setlocal
cd /d "%~dp0"
rem Force the port so an inherited SERVER_PORT cannot override application.yml
set "SERVER_PORT=8081"
echo ==================================================
echo  Survey System backend
echo  URL: http://localhost:8081
echo  Stop: Ctrl + C
echo ==================================================
echo.
call mvnw.cmd spring-boot:run
echo.
echo [backend stopped] press any key to close
pause >nul
