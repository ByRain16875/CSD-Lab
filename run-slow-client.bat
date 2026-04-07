@echo off
setlocal

echo Running slow client...
mvn compile exec:java -Dexec.mainClass="agent.client.SlowClient"

endlocal