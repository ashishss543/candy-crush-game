param([int]$Port = 8080)
$ErrorActionPreference = "Stop"

$javac = Get-Command javac -ErrorAction SilentlyContinue
$java = Get-Command java -ErrorAction SilentlyContinue
if (-not $javac -or -not $java) {
    $localJdk = Join-Path $env:USERPROFILE ".jdks\jdk-21\bin"
    if (Test-Path (Join-Path $localJdk "javac.exe")) {
        $javacPath = Join-Path $localJdk "javac.exe"
        $javaPath = Join-Path $localJdk "java.exe"
    } else {
        throw "Java JDK not found in PATH or $localJdk"
    }
} else {
    $javacPath = $javac.Source
    $javaPath = $java.Source
}

New-Item -ItemType Directory -Force -Path "out" | Out-Null
$sources = @(Get-ChildItem -Path "src" -Filter "*.java" -Recurse | Select-Object -ExpandProperty FullName)
Write-Host "Compiling $($sources.Count) Java source files..."
& $javacPath -encoding UTF-8 -d out @sources
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

Write-Host "Starting Candy Match Saga Java Server on http://localhost:$Port ..."
& $javaPath -cp out com.candymatch.CandyMatchServer $Port
