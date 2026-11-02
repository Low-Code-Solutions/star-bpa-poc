# frontend

React portal for the STAR BPA POC (ADR 0004). Walking skeleton (BPA-43): log in, see `/me`, log out. Vite + React + TypeScript;
API types generated from `api/openapi.yaml` with `openapi-typescript`, calls through `openapi-fetch` (S21).

```sh
npm ci
npm test        # component tests (Vitest, jsdom)
npm run dev     # http://localhost:5173, proxies /api to bpa-app on http://localhost:8081 (BPA-43 Q11)
```

Start the compose stack first (`docker compose up -d --build` from the repo root). Log in as a persona from
`backend/src/main/resources/personas.yaml` (for example `time-method-analyst`) with `DEMO_PASSWORD` from `.env`. If port 5173 is taken,
run `npx vite --port <free port>`.
