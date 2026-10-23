@echo off
cd /d "%~dp0"
set CLASSPATH=lib\sqlite-jdbc.jar;lib\slf4j-api.jar;lib\slf4j-nop.jar
java --enable-native-access=ALL-UNNAMED --add-modules jdk.httpserver -cp "%CLASSPATH%" server\Main.java