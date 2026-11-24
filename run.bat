@echo off
setlocal
set "PORT=%~1"
if "%PORT%"=="" set "PORT=8080"

set "JAVA_BIN=java"
set "JAVAC_BIN=javac"
where javac >nul 2>&1
if errorlevel 1 (
  if exist "%USERPROFILE%\.jdks\jdk-21\bin\javac.exe" (
    set "JAVA_BIN=%USERPROFILE%\.jdks\jdk-21\bin\java.exe"
    set "JAVAC_BIN=%USERPROFILE%\.jdks\jdk-21\bin\javac.exe"
  ) else (
    echo ERROR: Java JDK not found in PATH or %%USERPROFILE%%\.jdks\jdk-21
    exit /b 1
  )
)

if not exist out mkdir out
echo Compiling Candy Match Saga Java sources...
"%JAVAC_BIN%" -encoding UTF-8 -d out src\com\candymatch\util\*.java src\com\candymatch\model\*.java src\com\candymatch\engine\*.java src\com\candymatch\CandyMatchServer.java
if errorlevel 1 exit /b 1

echo Starting Candy Match Saga Java Server on http://localhost:%PORT% ...
"%JAVA_BIN%" -cp out com.candymatch.CandyMatchServer %PORT%
