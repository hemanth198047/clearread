# Compose API build fix (exit 127)

**Symptom:** `RUN ./gradlew bootJar` fails with exit code **127** during `docker compose build api`.

**Root cause:** On many Windows/macOS Git checkouts the `backend/gradlew` file lands in the Docker build context **without the executable bit** (and sometimes with CRLF). Invoking `./gradlew` then returns 127 (“command not found” / not executable).

**Fix:**
- API Dockerfile: `chmod +x gradlew`, strip `\r`, layered COPY for cache
- `.gitattributes`: force LF on `gradlew` / `*.sh`
- `start.sh` / `start.bat`: build first and fail with a clear message before health wait

## Working commands

```bash
docker compose -p clearread -f infra/compose.yaml build api web
docker compose -p clearread -f infra/compose.yaml up -d
curl -sf http://localhost:8080/v1/health
./scripts/start.sh
```
