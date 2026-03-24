@echo off
setlocal

echo Running load test...
mvn compile exec:java -Dexec.mainClass="agent.client.LoadTestClient"

endlocal