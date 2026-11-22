# LA Referencia Embedding UI

This React and TypeScript UI is integrated into the Spring Boot project. Production assets are built by the root Maven build and served from the same origin as the API. See the root README for packaging and execution.

For frontend development, run the API on port 8989 and then:

```sh
corepack enable
pnpm install --frozen-lockfile
pnpm dev
```

The development server runs on port 8990. Vite proxies `/api`, `/api-docs`, `/swagger-ui` and `/actuator` to the backend. Set `VITE_PROXY_TARGET` in `.env.local` if the development backend uses a different address. Requests from the UI use relative paths. A previous browser-local API URL does not affect the integrated UI.

Useful checks:

```sh
pnpm typecheck
pnpm lint
pnpm test
pnpm build
pnpm exec playwright test
```

Use Node.js compatible with the installed Vite and pnpm versions. The Maven build installs its own pinned build tools. Browser tests use mock responses; use a disposable service and synthetic data for full integration tests.
