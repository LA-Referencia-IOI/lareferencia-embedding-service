# React Administration UI Proposal

**Status:** Proposed for review. Implementation starts only after explicit approval.

## 1. Overview

Build a modern, bilingual administration for the LA Referencia Embedding Service as an independent React application.

The UI provides a unified interface for the supported API workflows, including collection and document management, embedding profiles, indexations, asynchronous jobs, embedding generation, semantic search, stored vector inspection, and operational monitoring.

The application must prioritize usability, maintainability, accessibility, and consistency with the existing API contract.

**Semantic search in the UI accepts text only.** Stored vector retrieval is a separate tool. The existing API retains its vector-query contract for other clients.

The frontend and backend must remain independently developed, built, configured, deployed, and operated.

## 2. Recommended technology stack

Use a modern, stable, and actively maintained React ecosystem. Select current stable versions compatible with one another at implementation time.

### 2.1. Core technologies

| Area | Recommended technology | Purpose |
|---|---|---|
| Framework | React | Component-based user interface |
| Language | TypeScript, strict mode | Type safety and maintainability |
| Build tool | Vite | Fast development and optimized production builds |
| Routing | React Router | Client-side navigation |
| Styling | Tailwind CSS | Consistent, responsive styling |
| UI components | shadcn/ui | Accessible, customizable UI components |
| UI primitives | Radix UI | Accessible interaction primitives where needed |
| Icons | Lucide React | Consistent iconography |
| Internationalization | i18next + react-i18next | Portuguese and Spanish translations |
| API types | openapi-typescript | Generate TypeScript definitions from OpenAPI |
| API client | openapi-fetch | Typed HTTP requests using the generated API contract |
| Server state | TanStack Query | Fetching, caching, mutations, and invalidation |
| Forms | React Hook Form | Form state and validation |
| Validation | Zod | Runtime validation and typed schemas |
| Form integration | @hookform/resolvers | Integrate Zod with React Hook Form |
| Notifications | Sonner | Toast notifications and feedback |
| Tables | TanStack Table | Sorting, filtering, and pagination interfaces |
| Dates | date-fns | Date formatting and manipulation |
| Testing | Vitest + React Testing Library | Unit and component tests |
| API mocking | Mock Service Worker (MSW) | Deterministic API tests |
| Browser testing | Playwright | End-to-end workflow verification |
| Code quality | ESLint + Prettier | Static analysis and consistent formatting |
| Package manager | pnpm | Dependency management and reproducible installs |

### 2.2. Technology decisions

**React with Vite instead of Next.js**

The UI is an authenticated or access-controlled administration application rather than a public, SEO-oriented website.

Server-side rendering and React Server Components are not required.

Vite provides a simpler deployment model, faster development workflow, and a clean separation from the Spring Boot backend.

**Tailwind CSS with shadcn/ui**

Use Tailwind CSS for layout, responsive behavior, and design tokens.

Use shadcn/ui for consistent interface components such as dialogs, forms, tables, tabs, navigation menus, and confirmation dialogs.

Components should remain locally customizable rather than introducing unnecessary dependencies on a proprietary design system.

**TanStack Query instead of Redux**

Most application state originates from the API.

Use TanStack Query for server state, including caching, loading states, background refetching, mutations, and cache invalidation.

Use React state or Context for small amounts of UI state, such as sidebar visibility and language selection.

Do not introduce Redux or another global state management library unless a demonstrated requirement justifies it.

**OpenAPI-generated types**

The running service's OpenAPI specification is the authoritative API contract.

Generate TypeScript types from the specification and use them throughout the shared API client.

Avoid manually duplicating API DTOs.

Custom code may be necessary for response headers, multipart uploads, cursor pagination, dynamic JSON values, and endpoint-specific behavior.

Generated types must not be manually modified.

## 3. Project organization

Create the frontend as a separate project:

`lareferencia-embedding-ui`

The existing backend remains at:

`lareferencia-embedding-service`

The frontend project owns its package manifest, source code, tests, build configuration, deployment files, and documentation.

The service repository remains the source of the API contract and this proposal.

### 3.1. Recommended directory structure

```text
lareferencia-embedding-ui/
├── public/
├── src/
│   ├── app/
│   │   ├── providers/
│   │   ├── router/
│   │   └── layouts/
│   ├── components/
│   │   ├── ui/
│   │   ├── forms/
│   │   ├── feedback/
│   │   ├── navigation/
│   │   └── data-display/
│   ├── features/
│   │   ├── collections/
│   │   ├── documents/
│   │   ├── indexations/
│   │   ├── profiles/
│   │   ├── search/
│   │   ├── vectors/
│   │   ├── jobs/
│   │   ├── api-v1/
│   │   └── operations/
│   ├── lib/
│   │   ├── api/
│   │   ├── config/
│   │   ├── errors/
│   │   └── utils/
│   ├── hooks/
│   ├── i18n/
│   │   └── locales/
│   │       ├── pt-BR/
│   │       └── es/
│   ├── styles/
│   ├── test/
│   └── main.tsx
├── tests/
│   └── e2e/
├── .env.example
├── index.html
├── package.json
├── pnpm-lock.yaml
├── tsconfig.json
├── vite.config.ts
├── eslint.config.js
├── components.json
└── README.md
```

Use a feature-oriented architecture.

Each feature should own its screens, API hooks, form schemas, components, and tests where appropriate.

Shared components should contain genuinely reusable functionality, not feature-specific business logic.

Avoid unnecessary abstraction layers, generic repositories, or complex architectural patterns that provide no practical benefit.

## 4. API integration and runtime configuration

Use the following references:

- `PLATFORM-GUIDE.md`
- `WORKFLOWS.md`
- The running service's OpenAPI specification at `/api-docs`

Before implementing each feature:

1. Inspect the corresponding API endpoints.
2. Compare request and response structures against OpenAPI.
3. Confirm behavior not fully described by the schema.
4. Identify asynchronous operations and required headers.
5. Implement the frontend using the verified contract.
6. Add tests for important request and state behavior.

Present equivalent API routes as one user action.

### 4.1. Independent deployment

Serve the frontend at `/` on its own host and port.

The API runs independently and may use a different host or port.

The API base URL must be configurable without changing application source code.

For example:

```env
VITE_API_BASE_URL=http://localhost:8080
```

For deployments requiring configuration after the frontend has been built, provide a runtime configuration mechanism rather than relying exclusively on Vite build-time variables.

Do not embed credentials or secrets in frontend environment variables.

### 4.2. Cross-origin requests

When frontend and API use different origins, configure CORS in the API or gateway.

Support the methods and request headers required by the implemented workflows, including:

- `Content-Type`
- `If-Match`
- `Idempotency-Key`
- Authorization headers when required by the deployment

Expose response headers required by the frontend, especially `ETag`.

A development proxy may be used as an alternative for local development.

Document the CORS requirements and provide deployment examples.

### 4.3. API client requirements

The shared API client must:

- Use OpenAPI-generated TypeScript types where practical.
- Centralize base URL configuration.
- Support JSON and multipart requests.
- Preserve response headers.
- Handle cursor-based pagination.
- Support variable JSON metadata.
- Normalize API errors without discarding error codes or details.
- Distinguish validation, conflict, authorization, network, and server errors.
- Support request cancellation where appropriate.
- Avoid automatic retries of unsafe mutations unless explicitly supported.
- Respect the API's idempotency contract.

Do not silently change the semantics of API requests.

### 4.4. Authentication and access control

The UI relies on the deployment's access-control infrastructure.

Do not implement an independent authentication system unless explicitly required.

Support the authentication mechanism selected for deployment, such as a gateway or an existing identity provider.

Never treat collection membership as tenant authorization.

The frontend must not assume that hiding an action provides security.

Authorization must be enforced by the API or its gateway.

## 5. Visual design and user experience

Use the [LA Referencia demo palette](https://la-referencia-ioi.github.io/lareferencia-semantic-demo/styles.css) as the visual reference.

### 5.1. Design tokens

| Role | Color |
|---|---|
| Primary | `#007F80` |
| Informational | `#275D84` |
| Main background | `#FFFFFF` |
| Secondary surface | `#F5F8F7` |
| Warning accents | Orange and yellow |
| Success and error | Accessible semantic colors |

Define these colors through centralized design tokens.

Support consistent typography, spacing, borders, input styles, and component states.

Use color together with text or icons when representing status.

### 5.2. Layout

Provide:

- Desktop sidebar navigation.
- Collapsible mobile navigation.
- Header with application identity and language selector.
- Breadcrumbs where useful.
- Consistent page headings and action placement.
- Responsive tables and forms.
- Clear empty, loading, success, and error states.
- Confirmation dialogs for destructive actions.
- Accessible keyboard focus and labeled controls.

Use skeletons or progress indicators for loading states.

Avoid excessive animations and unnecessary visual complexity.

Semantic search should be a focused workspace, with advanced options collapsed by default.

### 5.3. Accessibility

Target WCAG 2.2 AA for the frontend.

Use accessible components and semantic HTML.

Support keyboard navigation, visible focus, meaningful error messages, proper form labels, accessible dialogs, and sufficient color contrast.

Do not rely on color alone to communicate information.

## 6. Internationalization

Support:

- Portuguese (`pt-BR`), the default language.
- Spanish (`es`), selectable in the header.

Use i18next and react-i18next.

Persist the selected language locally.

Translate:

- Navigation and screen headings.
- Form labels and help text.
- Validation messages.
- Buttons and dialogs.
- Job and indexation statuses.
- Known API error codes.
- Notifications and empty states.

Update the document's `lang` attribute when the language changes.

Format dates and numbers according to the selected locale.

Preserve document source texts and metadata in their original language.

Use translation keys rather than hardcoded interface strings.

## 7. Screens and workflows

### 7.1. Collections and documents

Provide collection creation, listing, and inspection.

Show document and indexation counts and linked indexations.

Support document browsing with cursor pagination.

Allow lookup by document UUID or exact external ID.

Support:

- Document creation.
- Partial document updates.
- Explicit full-snapshot replacement.
- Individual text deletion.
- Document deletion.
- Metadata editing as JSON.
- Text editing through kind, language, and content fields.
- JSON and JSONL uploads, with or without precomputed embeddings.

Use a structured text editor for document text fields.

For metadata, use a JSON editor with syntax highlighting, formatting, and validation. A lightweight editor such as CodeMirror may be introduced for this purpose.

Do not introduce a large IDE-style editor dependency unless necessary.

### 7.2. Indexations

List global indexations and allow filtering by collection.

Display table and ANN states.

Show associated collections.

Support:

- Independent indexation creation.
- Creation and attachment in one operation.
- Attaching existing collections.
- Embedding generation for selected documents.
- Embedding generation for an entire selected collection.
- ANN building.
- Reconciliation.

Do not expose unsupported editing, deletion, or unlinking operations.

### 7.3. Embedding profiles

List, inspect, and create immutable embedding profiles.

Support HTTP and import-only profiles.

Display:

- Model identifier.
- Model revision.
- Vector dimensions.
- Preparation version.
- Document prefix.
- Query prefix.
- Provider configuration reference where applicable.

Request a configured provider reference only for HTTP profiles.

Do not expose profile editing or deletion.

### 7.4. Semantic search

Provide a dedicated search workspace.

The user enters query text and selects an indexation.

Optionally restrict the search to an attached collection.

Support:

- Exact search.
- Approximate search when available.
- Result count.
- Document or unit grouping.
- Optional text projection.
- Optional metadata projection.

Show each result's collection, document, and distance.

Use a clear results layout with expandable metadata and text details.

Send requests to:

`POST /api/v2/indexations/{indexation}/search`

Use `query.text`.

The search form must not expose vector input.

Use `collection_id` as an optional query parameter.

Default to exact search.

Enable approximate search only when the selected indexation reports the required capability.

For import-only indexations, require a compatible HTTP query profile.

Send the selected profile ID as `query.profile_id`.

Validate model and dimension compatibility according to the API contract.

Display an actionable message when no compatible executable profile exists.

### 7.5. Stored vectors

Provide a separate workspace for stored embedding inspection.

Look up a document by UUID or exact external ID within an indexation.

Support optional collection filtering.

When the API returns `ambiguous_external_id`, prompt the user to select or specify a collection.

Display:

- Associated model information.
- Vector dimensions where available.
- Vector previews.
- Document and text associations.
- Pagination for large responses.

Provide JSON copying.

Avoid rendering thousands of vector dimensions by default. Use collapsed previews and explicit expansion.

### 7.6. Jobs

Provide a unified job monitoring interface.

Track:

- Provisioning.
- Imports.
- Embedding generation.
- Maintenance and reconciliation.

Display job state, counters, timestamps, and paginated per-item errors where available.

Support retries only for eligible jobs.

After an asynchronous action receives `202 Accepted`, navigate or link immediately to the corresponding job.

Use TanStack Query polling while jobs are active, with a configurable interval and appropriate cleanup.

Stop unnecessary polling when jobs reach terminal states.

### 7.7. API v1

Provide a separate legacy API area.

Support:

- Store listing.
- Store creation.
- Embedding generation.
- v1 status inspection.

Keep v1 workflows visually separated from the primary v2 UI.

Do not mix incompatible API concepts.

### 7.8. Operations

Provide an operational overview containing:

- Service health.
- Available metrics.
- Metric lookup by name.
- Swagger UI link.
- OpenAPI JSON link.
- Swagger configuration link.

All operational links must use the configured API base URL.

Do not assume that operational endpoints are publicly accessible.

## 8. Interaction and data integrity rules

### 8.1. Document revisions

Read the document's `ETag` response header.

Send `If-Match` on edits and deletions.

When a revision conflict occurs:

- Preserve the user's draft.
- Explain that the document changed.
- Offer to reload the latest version.
- Avoid silently overwriting changes.

Confirm destructive actions.

Explain that deleting a source text retains its existing document vectors.

### 8.2. Idempotency

Generate one `Idempotency-Key` per logical submission when required.

Reuse the same key only when retrying an identical request.

A modified request must receive a new key.

Do not generate a new key automatically for a retry of an identical submission when that would defeat idempotency.

### 8.3. Asynchronous operations

Treat `202 Accepted` as successful submission, not completed execution.

Track the corresponding job through the jobs API.

Clearly distinguish accepted, running, completed, and failed operations.

### 8.4. Supported mutations

Expose only mutations supported by the API.

Profiles and indexations have no edit action.

Collection and indexation deletion and unlinking are outside scope.

Do not create frontend workflows for endpoints that do not exist.

## 9. Testing and quality assurance

### 9.1. Unit and component tests

Use Vitest and React Testing Library.

Test:

- Form validation.
- Request construction.
- API error handling.
- Query parameter generation.
- Idempotency behavior.
- Revision conflict handling.
- Search mode availability.
- Import-only profile compatibility.
- Locale switching.
- Pagination state.

### 9.2. API mocking

Use MSW to simulate API responses in isolated frontend tests.

Cover success, validation errors, conflicts, asynchronous acceptance, empty results, and network failures.

Mocks must follow the real OpenAPI contract.

### 9.3. End-to-end testing

Use Playwright for critical workflows.

Run integration and browser tests against a disposable service using synthetic documents and a deterministic embedding provider.

Verify:

- Collection and document management.
- JSON and JSONL imports.
- Job tracking.
- Embedding generation.
- Search.
- Stored vector retrieval.
- Revision conflicts.
- Language switching.
- Responsive navigation.

### 9.4. Production checks

Verify:

- Dependency installation.
- TypeScript compilation.
- Linting.
- Unit tests.
- Production build.
- Direct navigation to nested frontend routes.
- Cross-origin API requests.
- CORS behavior.
- `ETag` and `If-Match`.
- `Idempotency-Key`.
- Swagger and health links.
- Keyboard navigation.
- Loading, empty, and error states.

## 10. Development and deployment

Use pnpm as the package manager.

Document the commands for:

```bash
pnpm install
pnpm dev
pnpm lint
pnpm typecheck
pnpm test
pnpm build
pnpm preview
pnpm exec playwright test
```

Provide scripts in `package.json` where appropriate.

Run all frontend commands from the `lareferencia-embedding-ui` project root.

Include an `.env.example` with documented configuration values.

### 10.1. Production deployment

The production build must generate static assets suitable for serving through Nginx or another static web server.

Configure SPA fallback so direct navigation to nested frontend routes works.

Support deployment independently from the Spring Boot service.

A lightweight multi-stage Docker image with Nginx is recommended as an optional deployment mechanism.

Do not require Docker for local development.

Document:

- Local frontend address.
- Local API address.
- Production frontend and API addresses.
- Runtime or build-time API configuration.
- CORS requirements.
- Development commands.
- Production build commands.
- Static hosting configuration.
- Optional Docker deployment.

## 11. Implementation phases and acceptance criteria

Implementation must be incremental.

Each phase starts from the last approved baseline.

Do not proceed automatically to the next phase.

For every phase, record:

- Implemented features.
- Verification commands.
- Test results.
- Outstanding issues.
- Known limitations.
- Approval status.

### Phase 1. Foundation, collections, and documents

**Deliverables**

- Independent React project.
- Vite and TypeScript configuration.
- Tailwind CSS and shadcn/ui setup.
- Application shell and responsive navigation.
- Portuguese and Spanish localization.
- Shared OpenAPI-based API client.
- Collection and document management.
- Reusable form, table, and feedback components.

**Acceptance**

Verify cursor pagination, external ID lookup, partial updates, full replacement, deletion, and document revision conflicts.

### Phase 2. Profiles and indexations

**Deliverables**

- Profile listing and creation.
- Profile detail views.
- Global indexation listing.
- Independent indexation creation.
- Indexation creation and attachment.
- Collection-to-indexation association management.

**Acceptance**

Verify that the same indexation can appear under two attached collections.

### Phase 3. Jobs, imports, generation, and ANN

**Deliverables**

- JSON and JSONL uploads.
- Job monitoring and eligible retries.
- Embedding generation workflows.
- ANN build and reconciliation controls.
- Job progress and error reporting.

**Acceptance**

Verify imports with and without precomputed embeddings and a complete flow from asynchronous submission to stored vector retrieval.

### Phase 4. Semantic search and stored vectors

**Deliverables**

- Text-only semantic search.
- Exact and approximate modes.
- Search result grouping and projections.
- Collection filtering.
- Compatible query-profile selection.
- Stored vector inspection and pagination.

**Acceptance**

Verify that every UI semantic search sends text, that search spans two linked collections by default and honors the optional filter, and that import-only indexations require compatible HTTP query profiles for text search.

Verify ANN availability, stored vector pagination, and ambiguous external ID handling.

### Phase 5. API v1, operations, and release verification

**Deliverables**

- Legacy v1 workflows.
- Operations dashboard.
- Health and metrics inspection.
- Swagger and OpenAPI links.
- Deployment documentation.
- Production build verification.
- Accessibility and responsive review.

**Acceptance**

Verify v1 stores and embedding generation, operational links, both locales, keyboard navigation, responsive layouts, and loading, empty, and error states throughout the UI.

## 12. Engineering principles

Follow these principles throughout implementation:

1. **API-first:** The existing OpenAPI contract defines supported behavior.
2. **Feature-oriented:** Organize implementation around user workflows.
3. **Type-safe:** Prefer generated API types and strict TypeScript.
4. **Accessible:** Build keyboard-friendly, understandable interfaces.
5. **Incremental:** Deliver and verify one approved phase at a time.
6. **Simple:** Avoid unnecessary dependencies and abstractions.
7. **Independent:** Keep frontend deployment separate from the backend.
8. **Testable:** Cover critical workflows and request behavior.
9. **Consistent:** Reuse design tokens, components, and interaction patterns.
10. **Maintainable:** Favor readable code and explicit behavior over clever solutions.

## 13. Decisions for review

The following decisions require confirmation before implementation:

- The frontend uses React, TypeScript, and Vite.
- Tailwind CSS and shadcn/ui provide the design system.
- TanStack Query manages API server state.
- OpenAPI-generated types define API integration.
- Portuguese is the default language, with Spanish available.
- API v1 appears in a separate area.
- The frontend is mounted at `/` and can use a different host or port from the API.
- The API base URL is configurable.
- Authentication and authorization are provided by the deployment's access-control layer.
- Implementation proceeds in five independently verified and approved phases.

**No application implementation is included in this proposal.**