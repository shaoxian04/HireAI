# DDD conventions (backend)

Distilled from the `scaffolding-ddd-spring-boot` skill and SAD §2.4. **Invoke the skill before laying out packages or adding an aggregate.** Base package: `com.hireai`.

## The one rule

Dependencies point inward, never back:

```
controller → application → domain ← infrastructure
```

No layer imports a layer to its left. **The domain layer has zero framework/infrastructure imports.**

## Layer responsibilities

- **controller** — validate the request, call exactly one app service, wrap the result in `WebResult`. No business logic, no direct repository access. Request→`XxxInfo` mapping is done by the *app service*, not the controller.
- **application** — orchestrate use cases, CQRS-split: `XxxReadAppService` (returns DTOs directly) and `XxxWriteAppService` (`@Transactional`, returns only the aggregate ID; caller re-reads). **Outbound clients are invoked here**, never in the domain — fetch external data (webhook result, LLM ruling) and pass the *result* into a domain service as a plain `XxxInfo`.
- **domain** — aggregate roots + child entities (`XxxModel`), value objects, enums, repository **interfaces**, domain events, and one domain service per state transition. Depends on nothing.
- **infrastructure** — JPA repository impls (`XxxRepositoryImpl`) and outbound clients (`XxxClient`). Depends inward on domain interfaces.

## Package layout

```
controller/base|config              BaseController, WebResult, ResultCode, SecurityConfig, AuthInterceptor
controller/biz/<route-group>        controller + Request/DTO + converters (grouped by HTTP route, not aggregate)
application/biz/<aggregate>         XxxReadAppService, XxxWriteAppService
domain/biz/<aggregate>/{model,repository,service,enums,event,info}
infrastructure/repository/<aggregate>   XxxRepositoryImpl (JPA)
infrastructure/client                   AgentWebhookClient, ArbitrationClient
```

Domain/application/infrastructure are grouped **by aggregate**; controllers are grouped **by HTTP route group**.

## Naming suffixes (apply exactly)

`XxxModel` (aggregate root or child entity) · `XxxRepository` (one per root, interface in domain) · `XxxRepositoryImpl` (infra JPA) · `XxxQuery` (read/pagination) · `Verb+Noun+DomainService` (one per state transition) · `XxxDomainEvent` · `XxxInfo` (domain inbound carrier / event payload) · `XxxReadAppService` / `XxxWriteAppService` · `XxxController` · `XxxRequest` / `XxxDTO` / `XxxModel2DTOConverter` · `XxxClient`.

## The five rules that matter

1. **Aggregate = boundary.** Group domain code by aggregate. Reach child entities only through the root. Never cross-import between `domain/biz/<aggregate>` packages — go through an app service or a domain event.
2. **One repository per aggregate root.** Children load/save through the root. App/domain services depend on the **interface**, never the impl.
3. **CQRS split.** Reads via `XxxReadAppService` (DTOs, cache-safe). Writes via `XxxWriteAppService` (`@Transactional`, return the ID).
4. **One domain service per state transition** — `TaskSubmitDomainService`, `TaskAcceptDomainService`, not a `TaskDomainService` god-class.
5. **Controllers do three things only** — validate, call one app service, wrap in `WebResult`.

## HireAI aggregates & their domain services

See `data-model.md` for the aggregate/child/repository map. Routing is **not** an aggregate — it's `RoutingWriteAppService` (application layer) coordinating Task + Agent so the two domains never cross-import. Cross-aggregate flows use domain events: `TaskAcceptedDomainEvent`, `DisputeResolvedDomainEvent`, `ReputationDroppedBelowThresholdDomainEvent`, `SpecViolationDomainEvent`.
