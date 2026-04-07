@echo off
setlocal

echo Starting Agent Gateway server...
mvn compile exec:java -Dexec.mainClass="agent.gw.Main"

endlocal