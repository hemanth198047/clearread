# Compose API build fix

## Gradle distribution download timeout (Windows / flaky network)

**Symptom:** `docker compose build api` fails while downloading the Gradle zip:

```text
Downloading https://services.gradle.org/distributions/gradle-8.14.3-bin.zip
Exception: timeout (10000ms) / SocketTimeoutException: Connect timed out
```

The **web** image often still builds fine (npm registries differ).

**Root cause:** Older API Dockerfiles used a plain JDK base and ran `./gradlew`, which downloads the Gradle distribution during `docker build`. A short wrapper `networkTimeout` (10s) plus Docker Desktop networking can time out.

**Fix (v1.0.2+):**
- API Dockerfile build stage is based on official **`gradle:8.14.3-jdk21`** (distribution already in the image; no zip download for the tool itself).
- BuildKit cache mounts on `/home/gradle/.gradle` for dependency reuse.
- Wrapper `networkTimeout=120000` and `backend/gradle.properties` HTTP timeouts for **host** `./gradlew` builds.

### Immediate workarounds (any version)

```bash
# 1) Retry when the network is OK
docker compose -p clearread -f infra/compose.yaml build api

# 2) Pre-pull the Gradle base image, then rebuild
docker pull gradle:8.14.3-jdk21
docker compose -p clearread -f infra/compose.yaml build api

# 3) Host fallback: build JAR on the host, or use start.sh hybrid path
cd backend && ./gradlew bootJar --no-daemon
# or: ./scripts/start.sh  (falls back to host bootRun + Vite if Compose API is unhealthy)
```

Optional host timeout env (if wrapper still downloads the zip):

```bash
export GRADLE_OPTS="-Dorg.gradle.internal.http.connectionTimeout=120000 -Dorg.gradle.internal.http.socketTimeout=120000"
cd backend && ./gradlew bootJar --no-daemon
```

## Image build exit 127 (`./gradlew`)

**Symptom:** `RUN ./gradlew …` fails with exit code **127**.

**Root cause:** On many Windows/macOS Git checkouts `backend/gradlew` lands in the build context **without the executable bit** (and sometimes with CRLF). Invoking `./gradlew` then returns 127.

**Fix:**
- API Dockerfile: `chmod +x gradlew`, strip `\r` (still applied; Docker build uses image `gradle` CLI)
- `.gitattributes`: force LF on `gradlew` / `*.sh`
- `start.sh` / `start.bat`: build first and fail with a clear message before health wait

## Working commands

```bash
docker compose -p clearread -f infra/compose.yaml build api web
docker compose -p clearread -f infra/compose.yaml up -d
curl -sf http://localhost:8080/v1/health
./scripts/start.sh
```
