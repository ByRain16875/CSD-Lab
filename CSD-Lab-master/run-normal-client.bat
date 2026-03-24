@echo off
setlocal

echo Running normal client...
mvn compile exec:java -Dexec.mainClass="agent.client.NormalClient"

endlocal