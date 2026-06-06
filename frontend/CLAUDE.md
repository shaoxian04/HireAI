# frontend/ — HireAI demo UI

Next.js 16 (App Router) + TypeScript + Tailwind. The **Client + Builder** happy-path for the marketplace
demo; talks only to the Spring Boot API.

**Read [`../docs/details/frontend.md`](../docs/details/frontend.md) before changing this app** — the
`/api/*` proxy, the `api()` client + `WebResult` envelope, the auth context + localStorage scheme, the UI
kit, role guards, and the route map. Auth mechanics: [`../docs/details/identity-and-authz.md`](../docs/details/identity-and-authz.md).

> **Next.js 16** has breaking changes vs older versions — when an API differs from what you expect, check
> the official Next 16 docs (`node_modules/next/dist/docs/`) and heed deprecation notices.
