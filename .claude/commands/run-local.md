---
description: Build and run the service locally on port 8586, then verify it is healthy
argument-hint: "[profile: local|dev|prod] (default dev)"
allowed-tools: Bash, Read, Edit
---

Start the file transfer service locally using profile `$1` (default `dev` if not given).

1. Build: `./mvnw clean package -DskipTests`
2. Run it in the background: `./mvnw spring-boot:run -Dspring-boot.run.profiles=<profile>`
3. Poll `curl -s http://localhost:8586/actuator/health` until it returns `UP` or the process dies.
4. If startup fails, read the stack trace and diagnose. The usual cause is the `secrets` bean: outside the `test` profile the app needs `aws.secrets.enabled=true` and working AWS credentials (`~/.aws/credentials` or `AWS_ACCESS_KEY_ID`/`AWS_SECRET_ACCESS_KEY`), plus a reachable MySQL — `DataSourceConfig` is `@Primary` and always uses the MySQL driver. See CLAUDE.md.
5. Report the URL, the health status, and the log file location (`./logs/filetransfer.log`).

Do not modify configuration files to work around a startup failure — report what is missing and let me decide.
