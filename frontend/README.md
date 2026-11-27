# frontend

React portal for the STAR BPA POC (ADR 0004). Sign in, see `/me`, Logout (BPA-43, BPA-125); the task list and the Create Work Pack
screen (BPA-6). Vite + React + TypeScript, React Router and TanStack Query; API types generated from `api/openapi.yaml` with
`openapi-typescript`, calls through `openapi-fetch` (S21). Layout and rules: [ADR 0017](../docs/adr/0017-frontend-architecture.md)
and [CLAUDE.md](CLAUDE.md).

```sh
npm ci
npm test              # component and integration tests (Vitest, jsdom)
npm run lint          # ESLint
npm run format:check  # Prettier (npm run format to fix)
npm run build         # type check and production build
npm run dev           # http://localhost:5173, proxies /api to bpa-app on http://localhost:8081 (BPA-43 Q11)
```

Start the compose stack first (`docker compose up -d --build` from the repo root). Sign in as a persona from
`backend/src/main/resources/personas.yaml` (for example `time-method-analyst`) with `DEMO_PASSWORD` from `.env`. If port 5173 is taken,
run `npx vite --port <free port>`.

On Windows PowerShell, if scripts are disabled ("npm.ps1 cannot be loaded"), run `npm.cmd` instead of `npm` (for example
`npm.cmd run dev`), or use Command Prompt or Git Bash.
