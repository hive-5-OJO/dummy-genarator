@echo off
setlocal enabledelayedexpansion
set ROOT=%~dp0
set OUT=%ROOT%build
set CLASSES=%OUT%\classes
if not exist "%CLASSES%" mkdir "%CLASSES%"

rem collect sources
if exist "%OUT%\sources.txt" del "%OUT%\sources.txt"
for /r "%ROOT%src\main\java" %%f in (*.java) do (
  echo %%f>>"%OUT%\sources.txt"
)

javac -encoding UTF-8 -source 17 -target 17 -d "%CLASSES%" @"%OUT%\sources.txt"
jar --create --file "%OUT%\telecom-dummygen.jar" --main-class com.que.telecomdummy.Main -C "%CLASSES%" .
echo Built: %OUT%\telecom-dummygen.jar
