# Engineering Blackbox — GitHub API Polling MVP

A local-first Innovation Day dashboard that shows what is changing across your engineering repositories without requiring webhooks or a public URL.

## Architecture

```text
                         GitHub
                            ▲
                            │ REST API
                            │
                  every 3 minutes (configurable)
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

The application keeps the latest pull request activities in memory (default 500) and streams new updates to connected dashboards using Server-Sent Events. Each configured repository retains the latest 1 opened PR and 1 merged PR in the dashboard window.

## What it tracks

The poller reads GitHub Pull Request APIs and maps these pull request states:

- Pull requests: opened, merged
- Head commit message for each displayed PR
- Latest completed default-branch workflow run per repository, with failing repos highlighted in the dashboard
- A dedicated dashboard section for open pull requests older than the configured 3-day threshold

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
GITHUB_POLLING_INTERVAL_MS=180000
GITHUB_PULL_REQUESTS_PER_REPOSITORY=30
```

`GITHUB_POLLING_INTERVAL_MS` is optional. If not set, the backend defaults to polling every 3 minutes (`180000` ms).

`GITHUB_PULL_REQUESTS_PER_REPOSITORY` controls how many open and recently closed pull requests are inspected per repository on each poll. The legacy `GITHUB_INITIAL_EVENTS` variable is still accepted as a fallback.

`BLACKBOX_STALE_PR_DAYS` controls when an open pull request moves into the "PRs waiting too long" dashboard section. The default is `3` days.

## Run locally

```bash
cd backend
export GITHUB_TOKEN="github_pat_..."
export GITHUB_REPOSITORIES="your-org/identity-service,your-org/billing-service"
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

- `GET /api/activities?limit=<n>` — returns the latest activities, default `limit=6`
- `GET /api/stream/activities` — SSE stream
- `GET /api/status`
- `DELETE /api/activities`

## Important limitation

This is an Innovation Day MVP. Activities are stored only in memory and are lost when the application restarts. For a production version, replace polling with a GitHub App/webhooks and persistent storage.
