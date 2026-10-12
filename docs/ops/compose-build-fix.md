# Compose API build fix

## Failed to load `libnative-platform.so` (Windows Docker Desktop)

**Symptom:** `docker compose build api` fails during `gradle bootJar` with:

```text
Gradle could not start your build.
> Could not initialize native services.
   > Failed to load native library 'libnative-platform.so' for Linux amd64.
```

**Root cause:** BuildKit cache mounts over the **entire** `GRADLE_USER_HOME` (`/home/gradle/.gradle`) often break Gradle’s native-platform shared library on Docker Desktop (permissions / corrupt native cache), especially after `USER gradle` / `root` mixes in the Dockerfile.

**Fix (v1.0.3+):**
- Build stage is **`eclipse-temurin:21-jdk`** with Gradle copied from **`gradle:8.14.3-jdk21`** (`COPY --from=… /opt/gradle` + `PATH`) — still **no** `services.gradle.org` zip download.
- BuildKit caches only **`caches/`** and **`wrapper/`** under `GRADLE_USER_HOME`, never the whole home (native libs stay out of the cache mount).
- Single consistent user (`root`) for COPY + RUN — no `USER gradle` / root flip-flops.

### Immediate workarounds (any version, especially pre-v1.0.3)

```bash
git pull
docker builder prune -f   # clears corrupt BuildKit Gradle/native cache
docker compose -p clearread -f infra/compose.yaml build --no-cache api
```

## Gradle distribution download timeout (Windows / flaky network)

**Symptom:** `docker compose build api` fails while downloading the Gradle zip:

```text
Downloading https://services.gradle.org/distributions/gradle-8.14.3-bin.zip
Exception: timeout (10000ms) / SocketTimeoutException: Connect timed out
```

The **web** image often still builds fine (npm registries differ).

**Root cause:** Older API Dockerfiles used a plain JDK base and ran `./gradlew`, which downloads the Gradle distribution during `docker build`. A short wrapper `networkTimeout` (10s) plus Docker Desktop networking can time out.

**Fix (v1.0.2+ / refined in v1.0.3):**
- API Dockerfile obtains Gradle from official **`gradle:8.14.3-jdk21`** (`/opt/gradle` copy into the Temurin build stage; no zip download for the tool itself).
- Narrow BuildKit cache mounts (see above) for dependency reuse without native-platform breakage.
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
- API Dockerfile: `chmod +x gradlew`, strip `\r` (still applied; Docker build uses image `gradle` from `/opt/gradle`)
- `.gitattributes`: force LF on `gradlew` / `*.sh`
- `start.sh` / `start.bat`: build first and fail with a clear message before health wait

## Working commands

```bash
git pull
docker builder prune -f   # if you hit libnative-platform.so
docker compose -p clearread -f infra/compose.yaml build --no-cache api
docker compose -p clearread -f infra/compose.yaml build api web
docker compose -p clearread -f infra/compose.yaml up -d
curl -sf http://localhost:8080/v1/health
./scripts/start.sh
```
