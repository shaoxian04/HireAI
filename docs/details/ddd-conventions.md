# DDD conventions (backend)

Distilled from the `scaffolding-ddd-spring-boot` skill and SAD §2.4. **Invoke the skill before laying out packages or adding an aggregate.** Base package: `com.hireai`.

## The one rule

Dependencies point inward, never back. Since the COLA refactor each layer is its own **Maven module**, so this is now **compiler-enforced** — a wrong-way import won't even resolve:

```
controller ┐
infrastructure ┼─→ application ─→ domain ─→ utility      (hireai-main sits on top, wires all)
repository ┘
```

No module depends on one to its left. **`hireai-domain` and `hireai-utility` carry zero Spring on their classpath** — proven at build time, not just by review. Package names did **not** change in the split (repository code is still `com.hireai.infrastructure.repository.*`); only the owning Maven module did.

## Layer responsibilities

- **controller** — validate the request, call exactly one app service, wrap the result in `WebResult`. No business logic, no direct repository access. Request→`XxxInfo` mapping is done by the *app service*, not the controller.
- **application** — orchestrate use cases, CQRS-split: `XxxReadAppService` (returns DTOs directly) and `XxxWriteAppService` (`@Transactional`, returns only the aggregate ID; caller re-reads). **Outbound clients are invoked here**, never in the domain — fetch external data (webhook result, LLM ruling) and pass the *result* into a domain service as a plain `XxxInfo`.
- **domain** — aggregate roots + child entities (`XxxModel`), value objects, enums, repository **interfaces**, domain events, and one domain service per state transition. Depends on nothing but `utility`.
- **repository** (`hireai-repository`) — JPA repository impls (`XxxRepositoryImpl`), JPA entities, and read-side query-port impls. Implements interfaces owned by domain + application.
- **infrastructure** (`hireai-infrastructure`) — the *non-persistence* adapters: messaging (RabbitMQ), security (JWT/dispatch tokens), and outbound clients (`XxxClient`). Implements application ports.
- **utility** (`hireai-utility`) — cross-cutting primitives shared by every layer (`ResultCode`, all exception types, shared helpers). No Spring, no internal deps.

## Package layout (package → owning module)

```
hireai-utility        utility/result                          ResultCode, shared primitives (no Spring)
                      utility/exception                       DomainException + all exception types (any layer may throw)
hireai-domain         domain/biz/<aggregate>/{model,repository,service,enums,event,info}
hireai-application    application/biz/<aggregate>             XxxReadAppService, XxxWriteAppService, OutputSpecJsonMapper
                      application/port/{messaging,security,storage,query,task}   interfaces infra implements
hireai-repository     infrastructure/repository/<aggregate>   XxxRepositoryImpl + JPA entities (XxxDO) + read-query ports
hireai-infrastructure infrastructure/{messaging,security,client}   RabbitMQ, JWT, AgentDispatchClient, storage
hireai-controller     controller/base|config                  BaseController, WebResult, SecurityConfig, JwtAuthenticationFilter
                      controller/biz/<route-group>            controller + Request/DTO + converters (grouped by HTTP route)
hireai-main           HireAiApplication, application.yml, db/migration/V*, the whole test suite
```

Domain/application/repository/infrastructure are grouped **by aggregate**; controllers are grouped **by HTTP route group**. `ResultCode` lives in `hireai-utility` (shared by every layer); `WebResult`/`BaseController` stay in `hireai-controller`.

## Naming suffixes (apply exactly)

`XxxModel` (aggregate root or child entity) · `XxxDO` (JPA persistence entity, in `hireai-repository`) · `XxxRepository` (one per root, interface in domain) · `XxxRepositoryImpl` (infra JPA) · `XxxQuery` (read/pagination) · `Verb+Noun+DomainService` (one per state transition) · `XxxDomainEvent` · `XxxInfo` (domain inbound carrier / event payload) · `XxxReadAppService` / `XxxWriteAppService` · `XxxController` · `XxxRequest` / `XxxDTO` / `XxxModel2DTOConverter` · `XxxClient`.

## The five rules that matter

1. **Aggregate = boundary.** Group domain code by aggregate. Reach child entities only through the root. Never cross-import between `domain/biz/<aggregate>` packages — go through an app service or a domain event.
2. **One repository per aggregate root.** Children load/save through the root. App/domain services depend on the **interface**, never the impl.
3. **CQRS split.** Reads via `XxxReadAppService` (DTOs, cache-safe). Writes via `XxxWriteAppService` (`@Transactional`, return the ID).
4. **One domain service per state transition** — `TaskSubmitDomainService`, `TaskAcceptDomainService`, not a `TaskDomainService` god-class.
5. **Controllers do three things only** — validate, call one app service, wrap in `WebResult`.

## HireAI aggregates & their domain services

See `data-model.md` for the aggregate/child/repository map. Routing is **not** an aggregate — it's `RoutingWriteAppService` (application layer) coordinating Task + Agent so the two domains never cross-import. Cross-aggregate flows use domain events: `TaskAcceptedDomainEvent`, `DisputeResolvedDomainEvent`, `ReputationDroppedBelowThresholdDomainEvent`, `SpecViolationDomainEvent`.
