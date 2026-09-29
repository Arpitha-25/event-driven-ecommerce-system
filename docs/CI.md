# Continuous Integration (GitHub Actions)

[![CI](https://github.com/Arpitha-25/event-driven-ecommerce-system/actions/workflows/ci.yml/badge.svg)](https://github.com/Arpitha-25/event-driven-ecommerce-system/actions/workflows/ci.yml)

Every push to `main` and every pull request is built and tested automatically. The badge above, which also appears in the README, shows the result of the latest run.

Workflow: [`.github/workflows/ci.yml`](../.github/workflows/ci.yml)

---

## 1. The pipeline

```text
push to main / pull request / manual run
        │
        ▼
┌─────────────────────────────────────────────┐
│ Job 1: Build, unit and integration tests    │  ubuntu-latest · Temurin 21 · Maven cache
│   ./mvnw -B -ntp verify                     │
│     • unit tests (JUnit 5, Mockito)         │
│     • Testcontainers: real PostgreSQL 16 +  │
│       Kafka 3.8 in Docker on the runner     │
│     • Flyway builds every test database,    │
│       Hibernate validates it                │
└─────────────────────┬───────────────────────┘
                      │ only if job 1 passes
                      ▼
┌─────────────────────────────────────────────┐
│ Job 2: Full-flow system test                │
│   docker compose config --quiet             │  compose file is valid
│   ./mvnw -B -ntp install -DskipTests        │  artifacts (tests already ran)
│   ./mvnw -B -ntp -Psystem-tests             │  builds the service images with
│          -pl system-tests verify            │  docker compose, runs them with
│                                             │  PostgreSQL + Kafka, drives the
│                                             │  saga through the REST APIs
└─────────────────────────────────────────────┘
   on failure: test reports and service logs are uploaded as artifacts
```

| Choice | Reason |
|--------|--------|
| **Uses `./mvnw`** | The Maven wrapper pins Maven 3.9, so CI builds with the same Maven version as a developer's machine. |
| **Two jobs, the second after the first** | Fast feedback from job 1 (about 4 minutes). The slower Docker system test only runs once the code itself is known to be good. |
| **`concurrency` with `cancel-in-progress`** | A newer push to the same branch cancels the run that is still going, so time isn't spent on outdated commits. |
| **`permissions: contents: read`** | The workflow can read the code and nothing else. |
| **Artifacts on failure** | Surefire reports and each service container's log, so a red run can be investigated without re-running it. |

### Guard against tests that silently didn't run
The Testcontainers tests are marked `disabledWithoutDocker`. Without Docker they are **skipped, not failed**, so a build could be green while the integration tests never ran. After each test step, [`.github/scripts/test-summary.py`](../.github/scripts/test-summary.py) adds up the Surefire reports, publishes the counts on the run page, and **fails the job if no tests ran or any test was skipped**. Checked locally before pushing: real reports → 76 tests, exit 0; no reports → exit 1; a report with skipped tests → exit 1.

---

## 2. Fixes needed for CI

- **`mvnw` wasn't executable.** The repository was created on Windows, where git doesn't track the executable bit, so `mvnw` was stored as mode `100644`. On a Linux runner, `./mvnw` would have failed with `Permission denied`. It's now `100755` (`git update-index --chmod=+x mvnw`).
- **Deprecated actions.** GitHub flagged `actions/checkout@v4`, `actions/setup-java@v4` and `actions/upload-artifact@v4`, which run on the deprecated Node 20 runtime. They're now v7, v6 and v7, which run on Node 24. The inputs used (`distribution`, `java-version`, `cache`, `name`, `path`) were checked against each new version's `action.yml` first.

---

## 3. Results

**Before pushing:** the workflow's exact commands ran in a fresh clone through `./mvnw`:
- `verify`: 69 tests, 3 min 29 s
- `docker compose config`: valid
- system test: 7 tests, 4 min 14 s

All passed.

**On GitHub:**

| Run | Commit | Job 1: build, unit + integration | Job 2: system test | Test counts reported by GitHub |
|-----|--------|----------------------------------|--------------------|--------------------------------|
| [36596349985](https://github.com/Arpitha-25/event-driven-ecommerce-system/actions/runs/36596349985) | first workflow | ✅ 1 min 33 s | ✅ 1 min 43 s | *(the count check didn't exist yet)* |
| [36597180589](https://github.com/Arpitha-25/event-driven-ecommerce-system/actions/runs/36597180589) | test-count check added | ✅ 1 min 17 s | ✅ 2 min 08 s | **69 tests, 0 skipped** (inventory 31, order 22, product 16) · **7 system tests, 0 skipped** |
| [36598032731](https://github.com/Arpitha-25/event-driven-ecommerce-system/actions/runs/36598032731) | actions updated to v7 / v6 / v7 | ✅ 1 min 32 s | ✅ 1 min 50 s | 69 tests, 0 skipped · 7 system tests, 0 skipped; the Node 20 deprecation warnings are gone |

GitHub's runners finished much faster than this PC, which looked suspicious enough to check: if Docker had been missing on the runner, the integration tests would have been skipped and the jobs would still pass. The test-count step was added to rule that out, and GitHub's own annotation confirms **0 skipped**, so the Testcontainers tests really ran against PostgreSQL and Kafka on the runner.
