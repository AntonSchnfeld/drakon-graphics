@echo off
setlocal
cd /d %~dp0
call mvn install
if errorlevel 1 exit /b %errorlevel%
call mvn -f spike\pom.xml exec:java -Dexec.args=opengl
