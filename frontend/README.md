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

## Browser-generated semantic query embeddings

Semantic search can generate a query embedding in the browser using WebGPU. Select **Browser (WebGPU)** and a compatible model listed by the browser. The indexation profile must use `google/embeddinggemma-2`, dimensions 512 or 768 and `identity-v1` preparation. The indexation profile’s query prefix is applied. Server profiles are not presented as browser models; if no browser model is compatible, select server-side inference. The search request contains the generated vector, not the original query text or a query-profile ID.

The browser downloads the text-only `onnx-community/embeddinggemma-2-ONNX` model, pinned to revision `daa72c51243991dfcaf9f9137d2c573d8f7790c0`, in q4 precision. The first run needs network access to Hugging Face and can require a sizable download and GPU memory; later runs can use the browser cache. The model weights are not included in the JAR. The 512-dimensional output is truncated from 768 and L2-normalized.

WebGPU requires a supporting browser, a working adapter and a secure context (HTTPS or localhost). Plain HTTP on a remote IP is not a secure context. The UI reports an unavailable GPU or a model-load/inference failure; server-side inference remains available. Matching model ID and dimensions does not verify the underlying model revision: those checks do not prove that an imported vector was generated from identical weights, or that a fine-tuned model matches the public ONNX conversion. The q4 conversion can also affect retrieval quality. Compare against known vectors before relying on the results, especially for fine-tuned or externally imported embeddings.
