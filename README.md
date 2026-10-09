# Engineering Blackbox — GitHub API Polling MVP

A local-first Innovation Day dashboard that shows what is changing across your engineering repositories without requiring webhooks or a public URL.

## Architecture

```text
                         GitHub
                            ▲
                            │ REST API
                            │
                  every 10 seconds
                            │
                    ┌───────┴────────┐
                    │   Spring Boot  │
                    │                │
                    │ GitHub Poller  │
                    │      ↓         │
                    │ Activity Store │
                    │      ↓         │
                    │      SSE       │
                    └───────┬────────┘
                            │
                            ▼
                       React UI
```

The application keeps the latest events in memory (default 500) and streams new events to connected dashboards using Server-Sent Events.

## What it tracks

The poller reads GitHub repository Events and maps these event types:

- Pull requests: opened, merged
- GitHub Actions workflow runs (when exposed by the repository events API)

No PostgreSQL, Redis, Kafka, webhook endpoint, tunnel, or public URL is required for the local MVP.

## GitHub authentication

Create a GitHub token with the minimum read permissions needed for the repositories you want to observe. For private repositories, the token must have access to those repositories.

Do **not** commit the token.

Copy the example environment file:

```bash
cp .env.example .env
```

Edit `.env`:

```dotenv
GITHUB_TOKEN=github_pat_...
GITHUB_REPOSITORIES=your-org/identity-service,your-org/billing-service,your-org/customer-service
GITHUB_POLLING_INTERVAL_MS=10000
GITHUB_INITIAL_EVENTS=30
```

## Run locally

### Backend

```bash
cd backend
export GITHUB_TOKEN="github_pat_..."
export GITHUB_REPOSITORIES="your-org/identity-service,your-org/billing-service"
./gradlew bootRun
```

### Frontend during development

```bash
cd frontend
npm install
npm run dev
```

Open the Vite URL shown by the terminal. The Vite config proxies `/api` to Spring Boot on port 8080.

### Single host / port

Build React first:

```bash
cd frontend
npm install
npm run build
```

Copy the build into Spring Boot static resources:

```bash
cp -r dist/. ../backend/src/main/resources/static/
```

Then:

```bash
cd ../backend
./gradlew bootRun
```

Open:

```text
http://localhost:8080
```

## Docker

Create `.env` from `.env.example`, fill in the token and repositories, then:

```bash
docker compose up --build
```

Open:

```text
http://localhost:8080
```

## API

- `GET /api/activities?limit=100`
- `GET /api/stream/activities` — SSE stream
- `GET /api/status`
- `DELETE /api/activities`

## Important limitation

This is an Innovation Day MVP. Events are stored only in memory and are lost when the application restarts. The GitHub Events API is also not a perfect audit log and may have delivery/availability delays. For a production version, replace polling with a GitHub App/webhooks and persistent storage.
