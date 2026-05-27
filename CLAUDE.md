# AGENTS.md

This repository contains NexusMind（知枢）, a Spring Boot + Vue 3 AI knowledge-base system.

## Commands

Backend:

```bash
cd backend && mvn spring-boot:run
cd backend && mvn test
cd backend && mvn clean package
```

Frontend:

```bash
cd frontend
pnpm install
pnpm dev
pnpm typecheck
pnpm build
```

## Architecture

Backend Maven project: `backend`. Package root: `com.luky.nexusmind`.

Main backend modules:

- `client`: AI and external service clients
- `config`: Spring, security, Redis, Kafka, MinIO, Elasticsearch, WebSocket configuration
- `consumer`: asynchronous file-processing consumers
- `controller`: REST endpoints
- `model` / `entity`: persistence and search/message models
- `repository`: data access
- `service`: business logic
- `handler`: WebSocket chat handling
- `utils`: shared utilities

Frontend app lives in `frontend/src` and uses Vue 3, TypeScript, Vite, Naive UI, Pinia, Vue Router, UnoCSS and SCSS.

`homepage/` is a standalone static product page. It is not the main Vue app.

## Development Notes

- Keep API behavior compatible unless a task explicitly changes functionality.
- Keep secrets in environment variables, not committed YAML defaults.
- Use `NexusMind` / `知枢 NexusMind` for product-facing text.
- Do not reintroduce inherited author links, promotional copy, or original project branding.
