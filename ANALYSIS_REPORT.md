# ANALYSIS REPORT — Docker-User-Service-Docker

> **Generated:** 2026-09-28 · **Scope:** Read-only analysis — no files were modified.

---

## Table of Contents

1. [Build System](#1-build-system)
2. [Containerization](#2-containerization)
3. [Logging Pipeline](#3-logging-pipeline)
4. [CI/CD Pipeline](#4-cicd-pipeline)
5. [Missing / Broken Pieces](#5-missing--broken-pieces)
6. [README Cross-Check (Drift)](#6-readme-cross-check-drift)

---

## 1. Build System

### Sources examined
- `pom.xml` — Maven project descriptor
- `.mvn/wrapper/maven-wrapper.properties` — Maven Wrapper configuration
- `mvnw` / `mvnw.cmd` — Unix/Windows Maven wrapper scripts

### Key facts

| Property | Value |
|---|---|
| **Java version** | 17 (set via `<java.version>17</java.version>`) |
| **Spring Boot version** | 3.2.5 (via `spring-boot-starter-parent`) |
| **Spring Cloud version** | 2023.0.3 (BOM import, dependency-management only) |
| **Packaging type** | **WAR** (`<packaging>war</packaging>`) |
| **Artifact finalName** | `demo` → produces `target/demo.war` |
| **Group / Artifact / Version** | `com.heg` / `Docker-User-Service-Docker` / `1.0.9` |
| **Maven Wrapper version** | 3.3.4 (downloads Maven 3.9.14 from Maven Central) |
| **Maven binary used in CI** | Hard-coded local path to Maven **3.9.12** (`C:\maven\apache-maven-3.9.12\...`) — **differs from the wrapper's 3.9.14** |

### Runtime dependencies

| GroupId | ArtifactId | Version | Scope | Purpose |
|---|---|---|---|---|
| `org.springframework.boot` | `spring-boot-starter-web` | (managed) | compile | REST API + embedded Tomcat |
| `org.springframework.boot` | `spring-boot-starter-tomcat` | (managed) | **provided** | Embedded Tomcat excluded for WAR deploy |
| `org.springframework.boot` | `spring-boot-starter-data-elasticsearch` | (managed) | compile | ES client (Spring Data ES) |
| `org.springframework.boot` | `spring-boot-starter-data-redis` | (managed) | compile | Redis caching |
| `org.springframework.boot` | `spring-boot-starter-test` | (managed) | test | JUnit / Mockito |
| `com.internetitem` | `logback-elasticsearch-appender` | 1.6 | compile | Direct ES appender (declared but **not wired** in logback-spring.xml) |
| `net.logstash.logback` | `logstash-logback-encoder` | 7.4 | compile | JSON encoder + TCP appender for Logstash |

### Build plugins

| Plugin | Version | Configuration |
|---|---|---|
| `spring-boot-maven-plugin` | (managed by parent) | Standard Spring Boot repackaging |
| `jacoco-maven-plugin` | 0.8.11 | `prepare-agent` (default phase) + `report` (verify phase) → outputs `target/site/jacoco/jacoco.xml` |

### Distribution management (Nexus)

| Repository ID | URL |
|---|---|
| `nexus-snapshots` | `http://localhost:8081/repository/maven-snapshots/` |
| `nexus-releases` | `http://localhost:8081/repository/maven-releases/` |

> [!NOTE]
> Credentials for Nexus are not stored in `pom.xml` — they must exist in the external `C:\maven\settings.xml` file on the runner machine (see §5).

---

## 2. Containerization

### Sources examined
- `Dockerfile`
- `docker-compose.yml`

---

### Dockerfile

```dockerfile
FROM eclipse-temurin:17-jdk-alpine   # Base image
WORKDIR /app
COPY app.war app.war                  # Copies pre-built WAR from repo root
EXPOSE 8090
ENTRYPOINT ["java", "-jar", "app.war"]
```

| Attribute | Value |
|---|---|
| **Base image** | `eclipse-temurin:17-jdk-alpine` |
| **Exposed port** | `8090` |
| **Volumes** | None defined |
| **Environment variables** | None baked in (passed at runtime via `docker run -e`) |
| **Artifact required** | `app.war` must exist in repo root at build time (copied by CI from `target/demo.war`) |

> [!WARNING]
> A pre-built `app.war` **is committed to the repository**. This is a bad practice — binary artifacts should not be tracked in git. The CI pipeline overwrites it on each run, but the stale copy in source control may cause confusion.

---

### docker-compose.yml

The compose file defines **only the Logstash service** for local development use. Elasticsearch and Kibana are **not included** — they are started imperatively by the CI pipeline scripts.

| Service | Image | Ports | Volumes | Env Vars | Restart |
|---|---|---|---|---|---|
| `logstash` | `docker.elastic.co/logstash/logstash:7.17.10` | `5045:5045` | `./pipeline:/usr/share/logstash/pipeline` | `LS_JAVA_OPTS=-Xmx256m -Xms256m` | `unless-stopped` |

**Additional compose settings:**
- `extra_hosts: host.docker.internal:host-gateway` — allows Logstash to reach the host's Elasticsearch.
- Memory hard-limit: **512 MB** (`deploy.resources.limits.memory`).
- Network: default bridge.

> [!WARNING]
> The compose volume mounts `./pipeline` (a local `pipeline/` directory), but **no `pipeline/` directory exists in the repository**. Running `docker compose up` locally will fail immediately with a volume mount error. The CI pipeline works around this by using a `/tmp/logstash-pipeline/` directory instead, bypassing the compose file entirely.

> [!NOTE]
> The Spring Boot application service, Elasticsearch, Kibana, and Redis are **not defined** in `docker-compose.yml`. The compose file is only useful for Logstash during local development, and even then requires the missing `pipeline/` directory fix.

---

## 3. Logging Pipeline

### Sources examined
- `src/main/resources/logback-spring.xml`
- `logstash.conf`
- `pom.xml` (dependency declarations)
- `src/main/resources/application.properties`

---

### Log flow diagram

```
Spring Boot App
   │
   ├─► CONSOLE Appender
   │       Pattern: "%d{yyyy-MM-dd HH:mm:ss} [%thread] %-5level %logger{36} - %msg%n"
   │
   └─► LOGSTASH Appender (LogstashTcpSocketAppender)
           │  Destination : host.docker.internal:5045
           │  Encoder     : LogstashEncoder (JSON)
           │  Custom fields: {"app":"user-service","environment":"github-actions"}
           │  Connect timeout : 30 000 ms
           │  Reconnect delay : 10 000 ms
           │  Keep-alive      : 5 minutes
           │
           ▼
       Logstash (TCP input, port 5045)
           │  Input codec: json_lines
           │
           ▼
       Elasticsearch (output)
           │  Host  : http://host.docker.internal:9200
           │  Index : user-service-v2-logs-{yyyy.MM.dd}  (daily rollover)
           │
           └─► stdout (rubydebug) — debug echo to Logstash console
```

### Key observations

1. **Dual appenders** — every log event at `INFO` or above is sent to both console and Logstash simultaneously.
2. **`host.docker.internal`** — both the Spring Boot app (inside its container) and Logstash's `logstash.conf` output resolve Elasticsearch via `host.docker.internal:9200`. This means Elasticsearch is expected to run on the **Docker host**, not in the same Docker network. When running on the Ubuntu CI runner, this works because Logstash uses `host-gateway`. For the Spring Boot container, `SPRING_ELASTICSEARCH_URIS=http://host.docker.internal:9200` is injected at `docker run` time in Phase 4 of the CI.
3. **Kibana** — not part of the log pipeline itself; it reads indices from Elasticsearch directly. No Kibana wiring exists in code.
4. **Dead dependency** — `com.internetitem:logback-elasticsearch-appender:1.6` is declared in `pom.xml` with the comment *"Logback → Elasticsearch direct appender (no Logstash needed)"*, but `logback-spring.xml` uses `LogstashTcpSocketAppender` instead. The direct ES appender is **never instantiated**. This is dead/unused code.
5. **No log filtering** — all loggers inherit the root `INFO` level; no per-package level tuning.
6. **No Logstash filter section** — `logstash.conf` has no `filter {}` block. Raw JSON lines from the encoder are forwarded directly to ES with no enrichment, parsing, or field manipulation.

---

## 4. CI/CD Pipeline

### Source examined
- `.github/workflows/ci.yml`

### Trigger
- **Event:** `push` to branch `main` only.
- No `pull_request`, schedule, or `workflow_dispatch` triggers defined.

---

### JOB 1 — `pipeline`

| Attribute | Value |
|---|---|
| **runs-on** | `[self-hosted, windows-runner]` |
| **depends on** | — (first job) |

#### Environment variables (job-level `env:`)

| Variable | Value / Notes |
|---|---|
| `MAVEN_BIN` | `C:\maven\apache-maven-3.9.12\59fe...\bin\mvn.cmd` — **absolute local path** |
| `SETTINGS_XML` | `C:\maven\settings.xml` — **absolute local path** |
| `JAVA_HOME` | `C:\Program Files\Java\jdk-17` — **absolute local path** |
| `SONAR_TOKEN` | `sqa_b77a0c65feff9e8f0bcd782da843b9dfe8d7c640` — **⚠️ HARDCODED plaintext token in YAML** |
| `IMAGE_NAME` | `springboot-user-service` |
| `CONTAINER_NAME` | `springboot-user-service` |
| `HTTP_PROXY` | `http://192.168.9.112:808` — **⚠️ internal proxy IP hardcoded** |
| `HTTPS_PROXY` | `http://192.168.9.112:808` — **⚠️ internal proxy IP hardcoded** |
| `NO_PROXY` | `192.168.8.25,localhost,127.0.0.1` — **⚠️ internal IP hardcoded** |

#### Steps

| Step Name | What it does |
|---|---|
| **Checkout Code** | `actions/checkout@v4` with `fetch-depth: 0` (full history for SonarQube) and `clean: false` (preserves local workspace state) |
| **PHASE 1 — SonarQube Analysis** | Runs `mvn clean verify sonar:sonar` using hardcoded `SONAR_TOKEN`. Targets `http://localhost:9000`. Blocks on quality gate (`-Dsonar.qualitygate.wait=true`). Exits with error if gate fails. |
| **PHASE 2 — Upload to Nexus** | Runs `mvn deploy -DskipTests` using `SETTINGS_XML` for credentials. Pushes WAR to Nexus at `localhost:8081`. |
| **PHASE 3 — Build WAR + Docker Image** | Runs `mvn clean package -DskipTests`, copies `target/demo.war` → `app.war` in workspace root, then runs `docker build -t springboot-user-service .` |
| **PHASE 4 — Deploy Container** | Stops/removes old containers (`springboot-user-service` and `user-service`), starts new container: `docker run -d --name springboot-user-service -p 9090:8090 -e SPRING_DATA_REDIS_HOST=host.docker.internal -e SPRING_ELASTICSEARCH_URIS=http://host.docker.internal:9200 --restart unless-stopped springboot-user-service`. Waits 25 s, prints `docker ps` and `docker logs`. |

---

### JOB 2 — `ansible-deploy`

| Attribute | Value |
|---|---|
| **runs-on** | `[self-hosted, ubuntu-runner]` |
| **depends on** | `needs: pipeline` (Job 1 must succeed first) |

#### Steps

| Step Name | What it does |
|---|---|
| **Checkout Code** | `actions/checkout@v4` (default depth) |
| **PHASE 5 — Setup Infrastructure** | Full inline bash script that: ① stops/removes any existing `logstash` container; ② creates/verifies `app-network` Docker network; ③ starts `elasticsearch:7.17.10` if not running (ports 9200, 9300, single-node, 512 MB heap, 30 s wait); ④ starts `kibana:7.17.10` if not running (port 5601, links to elasticsearch, 30 s wait); ⑤ copies `logstash.conf` from `$GITHUB_WORKSPACE` to `/tmp/logstash-pipeline/`, starts fresh `logstash:7.17.10` (port 5045, volume-mounted conf, 40 s wait). |
| **PHASE 6 — Deploy via Ansible** | Prepends `/home/anchit/.local/bin` to `PATH`. Runs `ansible-playbook -i /home/anchit/github-actions-ansible/inventory.yml /home/anchit/github-actions-ansible/deploy-playbook.yml`. After playbook, runs `docker network connect app-network springboot-user-service`. |
| **PHASE 7 — Verify All Services** | Prints `docker ps`, curls Spring Boot (`localhost:9090/demo/users`), Elasticsearch (`localhost:9200/_cluster/health`), Kibana (`localhost:5601/api/status`), and tails Logstash logs. Final echo mentions Kibana at **hardcoded IP `172.24.204.45:5601`**. |

---

### Secrets / credentials usage summary

| Credential | Location | How referenced | Risk level |
|---|---|---|---|
| `SONAR_TOKEN` | `ci.yml` line 16 | Plaintext `env:` block | 🔴 **CRITICAL** — token is public in repo |
| Nexus credentials | `C:\maven\settings.xml` | Read by Maven at runtime | ⚠️ External file, not in repo |
| Ansible SSH keys | Implied by Ansible | None visible in workflow | ⚠️ Unknown — depends on playbook |

---

## 5. Missing / Broken Pieces

The following items are **referenced in the repo but do not exist** in the repository. They are external dependencies that must exist on the runner machines for the pipeline to work.

### 5.1 Self-Hosted Runners (both required)

| Runner label | OS | Required for |
|---|---|---|
| `windows-runner` | Windows | Job 1 (SonarQube, Nexus, Docker build, Phase 4 deploy) |
| `ubuntu-runner` | Ubuntu/Linux | Job 2 (ELK infra, Ansible deploy, verification) |

Neither runner is hosted by GitHub. Both must be registered to the repository via **Settings → Actions → Runners** and must be online when a push to `main` occurs.

---

### 5.2 External Services (not in repo, must run on Windows machine)

| Service | URL referenced | Config required |
|---|---|---|
| **SonarQube** | `http://localhost:9000` | Must run on the Windows runner host; project key `user-service` must exist |
| **Nexus Repository** | `http://localhost:8081` | Must run on the Windows runner host; repos `maven-snapshots` and `maven-releases` must exist |

---

### 5.3 Absolute local paths (Windows runner)

These paths are hardcoded in `ci.yml` and must exist exactly as specified:

| Path | Purpose |
|---|---|
| `C:\maven\apache-maven-3.9.12\59fe215c...\bin\mvn.cmd` | Maven binary (the SHA-like path segment suggests a specific installation layout) |
| `C:\maven\settings.xml` | Maven settings with Nexus + proxy credentials |
| `C:\Program Files\Java\jdk-17` | JDK 17 installation |

---

### 5.4 Internal Proxy Server

```
HTTP_PROXY=http://192.168.9.112:808
HTTPS_PROXY=http://192.168.9.112:808
NO_PROXY=192.168.8.25,localhost,127.0.0.1
```

A corporate/office HTTP proxy at `192.168.9.112:808` is hardcoded. This pipeline will **fail or misbehave** in any environment where this proxy is not reachable (e.g., running on GitHub-hosted runners, a different office network, or a developer laptop).

---

### 5.5 Ansible Inventory and Playbook Files (Ubuntu runner)

Referenced in Phase 6 but **not present in this repository**:

| File | Absolute path on runner |
|---|---|
| `inventory.yml` | `/home/anchit/github-actions-ansible/inventory.yml` |
| `deploy-playbook.yml` | `/home/anchit/github-actions-ansible/deploy-playbook.yml` |

The README provides example content for both files, but they must be manually created on the Ubuntu runner. The playbook runs `docker run ... springboot-user-service` — it pulls the image locally, so it requires the image built in Job 1 to be accessible on the Ubuntu host (no registry push/pull step exists between jobs).

> [!CAUTION]
> There is **no image transfer mechanism** between the Windows runner (where the Docker image is built) and the Ubuntu runner (where Ansible deploys it). The Ansible playbook references image `springboot-user-service` which only exists on the Windows machine. On the Ubuntu runner, `docker run springboot-user-service` will fail with `image not found` unless the image was separately pushed to a registry (e.g., Docker Hub, ECR) and pulled. **This is a critical gap in the pipeline.**

---

### 5.6 Missing `pipeline/` directory for docker-compose.yml

`docker-compose.yml` mounts `./pipeline:/usr/share/logstash/pipeline` but no `pipeline/` directory exists in the repository. Local `docker compose up` will fail.

---

### 5.7 Hardcoded SonarQube Token (Security Issue)

`SONAR_TOKEN: 'sqa_b77a0c65feff9e8f0bcd782da843b9dfe8d7c640'` is committed in plaintext to the public YAML file. This token should be:
1. **Revoked immediately** in SonarQube.
2. Stored as a GitHub Actions **repository secret** (`secrets.SONAR_TOKEN`) and referenced as `${{ secrets.SONAR_TOKEN }}`.

---

### 5.8 Committed Binary Artifact (`app.war`)

`app.war` (the compiled Spring Boot WAR) is committed to the repository root. Binary artifacts should not be version-controlled. It will cause:
- Inflated repository size over time.
- Risk of stale artifact being used if CI fails before the copy step.

---

### 5.9 Hardcoded Kibana IP in Verify Step

```bash
echo " Kibana        -> http://172.24.204.45:5601"
```

The final summary in PHASE 7 hardcodes the Ubuntu machine's IP address. This is informational only (not used functionally), but it makes the output misleading in any other environment.

---

## 6. README Cross-Check (Drift)

Overall, the README is well-written and accurately reflects the intent of the pipeline. However, the following discrepancies exist between the README and the actual implementation:

### 6.1 Redis setup is documented but NOT in the CI pipeline

**README states (§ Project Overview, bullet 5):**
> "Sets up full infrastructure (Redis, Elasticsearch, Kibana, Logstash) on Ubuntu"

**README §7 documents Redis startup:**
```bash
docker run -d --name redis-cache --network app-network -p 6379:6379 ...
```

**Actual ci.yml (PHASE 5):**
Redis is **never started** in the CI pipeline. Only Elasticsearch, Kibana, and Logstash are started. The Spring Boot container in Phase 4 passes `SPRING_DATA_REDIS_HOST=host.docker.internal`, which assumes Redis is already running externally. If Redis is not pre-existing on the Ubuntu host, the app will start but cache operations will silently fail or throw connection errors.

---

### 6.2 Maven version mismatch

**README Tech Stack table:**
> "Apache Maven — 3.9.12"

**`.mvn/wrapper/maven-wrapper.properties`:**
> Downloads Maven `3.9.14`

**`ci.yml`:**
> Uses hardcoded path to Maven `3.9.12` (`C:\maven\apache-maven-3.9.12\...`)

Three different versions are implied. The wrapper would download 3.9.14, but the CI bypasses the wrapper entirely and uses a local 3.9.12 installation.

---

### 6.3 Nexus `settings.xml` server ID mismatch

**README §5 documents `settings.xml` with server ID `nexus`:**
```xml
<server>
  <id>nexus</id>
  ...
</server>
```

**`pom.xml` `<distributionManagement>` uses:**
```xml
<id>nexus-snapshots</id>
...
<id>nexus-releases</id>
```

The `<server>` IDs in `settings.xml` must match the `<id>` values in `<distributionManagement>`. The README example uses `nexus`, but `pom.xml` expects `nexus-snapshots` and `nexus-releases`. If the actual `settings.xml` on the runner only defines `<id>nexus</id>`, the `mvn deploy` step will fail with an authentication error.

---

### 6.4 `docker-compose.yml` described as "Local dev docker-compose" but is incomplete

**README §Project Structure says:**
> `docker-compose.yml — Local dev docker-compose`

The compose file only defines Logstash. The README's "Manual (if needed)" section (§ How to Run) starts ES, Kibana, and Redis with `docker run` commands — consistent with what the CI does — but implies `docker-compose.yml` would handle the full stack locally. It does not.

---

### 6.5 Ansible playbook in README omits network connection

**README deploy-playbook.yml example:**
```yaml
- name: Run new container
  shell: |
    docker run -d \
      --name springboot-user-service \
      -p 9090:8090 \
      --restart unless-stopped \
      springboot-user-service
```

**Actual ci.yml Phase 6 (after ansible-playbook):**
```bash
docker network connect app-network springboot-user-service || true
```

The README playbook does not include connecting the container to `app-network`. The actual CI adds this step after Ansible. If someone uses the README's playbook verbatim, the Spring Boot container won't be on `app-network` and won't be able to communicate with Elasticsearch/Kibana/Logstash on that network.

---

### 6.6 Spring Boot version — minor description drift

**README Tech Stack:**
> "Spring Boot — 3.x"

**`pom.xml`:**
> `3.2.5`

Minor but worth noting for precision.

---

### 6.7 Project structure in README omits key files

**README §Project Structure does not list:**
- `app.war` (committed binary artifact)
- `src/main/resources/application.properties`
- `.mvn/` directory and Maven wrapper files
- Exception classes under `src/main/java/com/heg/exception/`

These are present in the actual repository.

---

## Summary Table

| Area | Status | Severity |
|---|---|---|
| Build system (Java 17, SB 3.2.5, WAR) | ✅ Consistent | — |
| Dockerfile base image and port | ✅ Correct | — |
| `app.war` committed to git | ⚠️ Bad practice | Medium |
| `docker-compose.yml` missing `pipeline/` dir | 🔴 Broken locally | High |
| ELK log pipeline (app → Logstash → ES) | ✅ Functional | — |
| Dead `logback-elasticsearch-appender` dependency | ⚠️ Dead code | Low |
| No Logstash filter block | ⚠️ Minor gap | Low |
| `SONAR_TOKEN` plaintext in YAML | 🔴 Security critical | Critical |
| Internal proxy IPs hardcoded in YAML | 🔴 Not portable | High |
| No image registry push between jobs | 🔴 Pipeline broken | Critical |
| Redis not started in CI (but app needs it) | 🔴 Runtime gap | High |
| Ansible files missing from repo | ⚠️ External dependency | High |
| Maven version mismatch (3.9.12 vs 3.9.14) | ⚠️ Minor inconsistency | Low |
| Nexus server ID mismatch in README | ⚠️ Documentation bug | Medium |
| Ansible playbook missing `app-network` join | ⚠️ Documentation gap | Medium |
| Self-hosted runners must be pre-registered | ℹ️ Documented prerequisite | Info |
| SonarQube / Nexus must be pre-running | ℹ️ Documented prerequisite | Info |
