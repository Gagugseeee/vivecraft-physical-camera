@echo off
setlocal
where gradle >nul 2>nul
if errorlevel 1 (
  echo ERROR: Gradle 9.6 not found. Install Gradle 9.6 and JDK 25 first.
  exit /b 1
)
gradle --no-daemon build
if errorlevel 1 exit /b 1
echo.
echo Done. See build\libs\ for the mod JAR.
