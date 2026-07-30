# Interview Coach

[简体中文](README.md) | **English**

Interview Coach is an AI-powered mock interview platform for job seekers. It generates personalized interviews from a candidate's resume and target position, then produces an assessment report and an actionable growth plan.

> This project is under active development. It currently uses a frontend-backend separated modular monolith architecture. The `gateway` directory is reserved for a future standalone gateway service.

## Current Capabilities

- User registration, login, profile management, and JWT authentication
- Resume upload, parsing, confirmation, and candidate profile management
- Job description entry, parsing, confirmation, and public position management
- Personalized mock interviews, answer history, interview reports, and growth plans
- Administration for users, positions, question banks, and audit logs
- Multi-model routing with an explicit real-model / mock switch

## Tech Stack

| Area | Technologies |
| --- | --- |
| Backend | Java 21, Spring Boot 3.3.2, Spring Security, Spring Data JPA, Spring AI |
| Data | MySQL, Redis; H2 for local configuration and tests |
| Frontend | Vue 3, TypeScript, Vite 5, Pinia, Vue Router, Element Plus |
| Document parsing | Apache PDFBox |

## Project Structure

```text
interview-coach/
├── backend/            # Spring Boot backend
├── frontend/web/       # Vue web frontend
├── gateway/            # Placeholder for a standalone gateway
├── docs/design/        # Design documents for each domain
└── DESIGN.md           # Overall project design
```

## Requirements

- JDK 21
- Maven 3.6.3 or later
- Node.js 18 or later
- npm
- MySQL 8
- Redis 6 or later

## Quick Start

### 1. Prepare the Database

Create the default database:

```sql
CREATE DATABASE interview_coach
  CHARACTER SET utf8mb4
  COLLATE utf8mb4_unicode_ci;
```

After creating the database, manually execute the current migration baseline in this order, then start the backend:

1. `backend/src/main/resources/db/migration/V1__init_schema.sql`
2. `backend/src/main/resources/db/migration/V2__resume_profile_draft_analysis.sql`
3. `backend/src/main/resources/db/migration/V3__resume_ai_task_control.sql`

Never edit an applied versioned script. Hibernate only validates the entity mappings and never creates or modifies the schema automatically.

The backend reads the following environment variables. Set them for your local environment, and never commit real passwords or secrets to the repository.

| Environment variable | Required | Description |
| --- | --- | --- |
| `MYSQL_HOST` | No | MySQL host; defaults to `localhost` |
| `MYSQL_PORT` | No | MySQL port; defaults to `3306` |
| `MYSQL_DB` | No | Database name; defaults to `interview_coach` |
| `MYSQL_USER` | Recommended | MySQL username |
| `MYSQL_PASSWORD` | Yes | MySQL password |
| `REDIS_HOST` | No | Redis host; defaults to `localhost` |
| `REDIS_PORT` | No | Redis port; defaults to `6379` |
| `REDIS_PASSWORD` | No | Redis password; leave empty when authentication is disabled |
| `REDIS_DB` | No | Redis logical database number; defaults to `0` |
| `JWT_SECRET` | Recommended | JWT signing key; use a sufficiently long random value |

PowerShell example:

```powershell
$env:MYSQL_USER="root"
$env:MYSQL_PASSWORD="<your-password>"
$env:JWT_SECRET="<your-random-secret>"
```

### 2. Start the Backend

```powershell
cd backend
mvn spring-boot:run
```

The backend runs at `http://localhost:8080` by default. All API endpoints use the `/api/v1` prefix.

Different repository profiles may enable a real model, so do not assume that a missing API key automatically selects the mock implementation. Before startup, check `resume.llm.enabled` and the selected vendor's `enabled` flag. Set `RESUME_LLM_ENABLED=false` when external model calls must be disabled. When enabling a real model, configure its API key through an environment-variable placeholder and never commit a real credential to shared configuration. The currently reserved vendor variables are:

- `DASHSCOPE_API_KEY`
- `OPENAI_API_KEY`
- `ZHIPU_API_KEY`

### 3. Start the Frontend

Open another terminal:

```powershell
cd frontend/web
npm install
npm run dev
```

Open `http://localhost:5173`. The development server proxies `/api` requests to `http://localhost:8080`.

## Local Profile Notes

`backend/src/main/resources/application-local.yml` uses an H2 file database, but it currently contains machine-specific absolute paths and enables a specific model configuration. Before using the `local` profile, replace the database and upload paths with writable paths on your machine, then verify the model switches and API key configuration.

## Database Schema Management

- Manually executed versioned SQL manages the MySQL schema. Scripts are stored in `backend/src/main/resources/db/migration/`.
- For a new environment, run `V1__init_schema.sql`, `V2__resume_profile_draft_analysis.sql`, and `V3__resume_ai_task_control.sql` in order. Add higher-version scripts for subsequent changes.
- Never edit an applied SQL file, and record the latest applied version in deployment records.
- The default profile uses `spring.jpa.hibernate.ddl-auto=validate`, so Hibernate only validates the schema.
- `backend/src/main/resources/db/schema.sql` is a deprecation notice for the old entry point and is not executed.
- The `local` and `test` profiles use H2 and retain their existing `update` and `create-drop` strategies.

## Verification

Run backend tests:

```powershell
cd backend
mvn test
```

Build the frontend:

```powershell
cd frontend/web
npm run build
```

## Development Workflow

Create a dedicated feature branch from `develop`:

```powershell
git switch develop
git pull --ff-only
git switch -c feature/<feature-name>
```

After development, merge the feature branch into `develop`. Merge `develop` into `master` when preparing a stable release.

## Design Documents

- [Overall design](DESIGN.md)
- [User module](docs/design/user-module.md)
- [Resume module](docs/design/resume-module.md)
- [Position module](docs/design/position-module.md)
- [Interview module](docs/design/interview-module.md)
- [Growth module](docs/design/growth-module.md)
- [Database design](docs/design/database-design.md)
- [Frontend prototype](docs/design/frontend-prototype.md)
- [Gateway plan](gateway/README.md)
