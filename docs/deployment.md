# Deployment

Slim by design: a single Docker image built by sbt-native-packager, configured by environment variables, and run wherever you run containers (k8s, ECS, Nomad, plain `docker run`).

The repo doesn't ship infrastructure-as-code, a Helm chart, or a CI pipeline. Those are deployment-target-specific; you bring them. What's here is the bit *every* deployment needs: a reproducible image and a clear list of what to feed it.

## Building the image

sbt-native-packager is enabled in `build.sbt`:

```scala
enablePlugins(JavaServerAppPackaging)
dockerCommands += Cmd("ARG", "BUILD_VERSION")
dockerCommands += Cmd("ENV", "APP_VERSION=$BUILD_VERSION")
dockerRepository    := sys.env.get("DOCKER_REPO")
packageName         := "madrileno"
dockerBaseImage     := "azul/zulu-openjdk:21"
Docker / daemonUser := "noroot"
dockerUpdateLatest  := true
```

Two build commands cover the lifecycle:

```bash
# Build the image into the local Docker daemon.
sbt --client "Docker/publishLocal"

# Build and push to the registry pointed at by DOCKER_REPO.
DOCKER_REPO=ghcr.io/your-org sbt --client "Docker/publish"
```

`Docker/publishLocal` produces `madrileno:1.0.0-SNAPSHOT` (matching `version` in `build.sbt`) and `madrileno:latest`. `Docker/publish` is the same plus a push to `$DOCKER_REPO/madrileno:<version>` and `$DOCKER_REPO/madrileno:latest`.

To stamp the image with a meaningful version (git SHA, semver tag, CI build number), pass it through `BUILD_VERSION`:

```bash
docker build --build-arg BUILD_VERSION=$(git rev-parse --short HEAD) ...
```

`build.sbt` exposes that as the `APP_VERSION` env var inside the running container, which `application.conf` then reads via `version = ${?APP_VERSION}`. Anywhere the app reports its version (logs, OTEL `service.version`), the value is whatever you passed.

## What's in the image

- **Base**: `azul/zulu-openjdk:21`. Production-grade JDK, regular security updates, multi-arch.
- **Daemon user**: `noroot`. The app doesn't run as root.
- **Layout**: native-packager's standard — a launcher script under `/opt/docker/bin/madrileno`, JAR + dependencies under `/opt/docker/lib/`, configuration via JVM flags + env vars.
- **Entry point**: `bin/madrileno`. Launches the JVM with the project's `Main`.

The image is *not* multi-stage; it's whatever `JavaServerAppPackaging` builds plus the two extra commands for `BUILD_VERSION`. If you want a slimmer base (Alpine, distroless), swap `dockerBaseImage`. If you want a buildkit-style multi-stage build for shrink, drop the native-packager defaults and write your own `Dockerfile` referencing the universal package output (`sbt --client "Universal/packageBin"`).

## Running the image

The container needs environment variables for every `${?VAR}` substitution in `application.conf`. See [configuration.md](configuration.md) for the full list; the production-relevant subset is:

- **App identity** — `APP_ENVIRONMENT=prod`, `APP_VERSION=<git sha>`.
- **HTTP** — `INTERFACE`, `PORT`, `BASE_URL`, `MAX_REQUEST_SIZE`. Bind to `0.0.0.0` inside the container; expose the port via your orchestrator.
- **Postgres** — `PG_HOST`, `PG_PORT`, `PG_DATABASE`, `PG_USER`, `PG_PASSWORD`. Provision with whatever your platform offers (RDS, Cloud SQL, managed Postgres).
- **OpenTelemetry** — `OTEL_*` per [observability.md](observability.md). Point at your real OTLP receiver.
- **Mailer** — `MAILER_HOST`, `MAILER_PORT`, `MAILER_USERNAME`, `MAILER_PASSWORD`, `MAILER_FROM_ADDRESS`, `MAILER_TLS=true`. SES, SendGrid, internal SMTP relay — anything that speaks SMTP.
- **Auth** — `JWT_SECRET` (real secret; inject from your secrets manager, never bake into the image). `FIREBASE_PROJECT_ID` if you use Firebase login — that's just the project id (not a secret); ID-token verification fetches Google's public certs. Leave it unset to disable Firebase auth.
- **Object storage** — `S3_ENDPOINT`, `S3_REGION`, `S3_BUCKET`, `S3_ACCESS_KEY_ID`, `S3_SECRET_ACCESS_KEY`. Point at real S3 or any S3-compatible store.
- **Admin auth** — `ADMIN_USER`, `ADMIN_PASSWORD`. Gates `/admin/*` (health, jobs UI, mail previews in dev). Strong password.

The dev-stack `.env` is *not* a production template; it's a local-development convenience. For real deploys, generate the env from your platform's secrets store + non-secret config map.

## Migrations are a separate step

The app does **not** auto-migrate on startup. Run them as a discrete deploy step before rolling out the new image:

```
build image → push image → run migrations → roll out new pods
```

The Docker image ships a second launcher, `bin/migrate-main`, that runs Flyway against the same `PG_*` env vars the app reads. Same image, same Git SHA, same config — schema and code can't drift apart. Run it as a one-shot container (or whatever your platform's equivalent is) gated before the new pods roll out.

Two reasons this is the right separation:

- **Migrations are slow and rare.** Running them on every pod start would slow rollouts and make crash-loop debugging painful.
- **Migrations are dangerous.** Locking, long-running, can fail mid-way. They want a single owner per deploy, not N pods racing.

For zero-downtime rollouts, follow the standard expand/contract dance:

1. Migration that's backwards-compatible with the old code (add column, NOT NULL with default).
2. Deploy new code that uses the new shape.
3. Cleanup migration in a later deploy (drop old column, etc.).

## Health checks

The deep `/admin/health-check` endpoint exists (Postgres + SMTP probe) but it's gated by Basic Auth — designed for human ops checks, not orchestrator probes. For Kubernetes-style probes, hit `/v1/health-check` (unauthenticated, lightweight). See [http.md](http.md) and the `HealthCheckModule` for what's wired.

If you need a richer liveness/readiness probe, add an unauthenticated endpoint (or a separate port) that runs the same checks without the auth gate. Don't expose `/admin/*` to the orchestrator.

## What this template doesn't ship

Things you'll likely want and need to add yourself:

- **CI pipeline** — `sbt verifyAll` is the gate locally; wire it (or `compile + test + scalafmtCheckAll`) into GitHub Actions / GitLab CI / whatever you use.
- **Image scanning** — Trivy, Snyk, your registry's own scanner.
- **Helm chart / k8s manifests / Terraform** — too target-specific to live here.
- **Secrets management** — assumes you have one (AWS Secrets Manager, k8s secrets, Vault). The app reads env vars; the secrets system is responsible for getting them there.
- **Blue/green or canary tooling** — orchestrator-level concern.

The image and the env-var contract are stable; the rest is yours to assemble.

## Where to look next

- [configuration.md](configuration.md) — the full env-var contract.
- [observability.md](observability.md) — what to point `OTEL_*` at; what shows up where.
- [database.md](database.md) — migration mechanics.
- [dev-workflow.md](dev-workflow.md) — running migrations locally (`runMain madrileno.main.MigrateMain`) and the `flyway*` inspection tasks.
