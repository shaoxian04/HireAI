# DDD conventions (backend)

What the backend actually does. **Invoke `scaffolding-ddd-spring-boot` before laying out packages or adding an aggregate.** Base package: `com.hireai`. The reasons behind these choices are in [`architecture-decisions.md`](architecture-decisions.md).

## The one rule

Dependencies point inward. Each layer is a Maven module, so a wrong-way import won't compile:

```
controller ┐
infrastructure ┼─→ application ─→ domain ─→ utility      (hireai-main sits on top, wires all)
repository ┘
```

`hireai-domain` and `hireai-utility` have no Spring on their classpath. Repository code is still in package `com.hireai.infrastructure.repository.*`, even though its module is `hireai-repository`.

## Layers

- **controller**: validates the request, builds the `XxxInfo`, calls the app service, converts the returned model to a DTO, wraps it in `WebResult`. No business logic and no repository access. A write endpoint may call a write service and then a read service to return the fresh state.
- **application**: orchestrates use cases: transaction, load, invoke the domain, persist through the domain repository interface, publish events, check ownership. Rules belong in the domain.
- **domain**: aggregates (`XxxModel`), value objects, enums, repository interfaces, domain events, domain services.
- **repository**: `XxxRepositoryImpl`, JPA entities (`XxxDO`), `XxxJpaRepository`, read-side query implementations.
- **infrastructure**: messaging, security, outbound clients, implementing application ports. RabbitMQ consumers live here, so `TaskDispatchConsumer` calls `AgentDispatchClient` directly.
- **utility**: `ResultCode`, exceptions, hashing helpers.

## Package layout

```
hireai-utility        utility/{result,exception,hash}
hireai-domain         domain/biz/<subdomain>[/<aggregate>]/{model,repository,service,enums,event,info}
                      domain/shared/model                      Money
hireai-application    application/biz/<subdomain>[/<aggregate>]   app services + impl/
                      application/biz/admin                    AdminReadAppService, cross-subdomain admin reads
                      application/biz/adjudication/port        arbitration ports
                      application/port/{messaging,query,security,storage,task,webhook}
                      application/config                       DomainServiceConfig wires domain services
hireai-repository     infrastructure/repository/<subdomain>[/<aggregate>], infrastructure/repository/admin
hireai-infrastructure infrastructure/{adjudication,client,messaging,security,webhook}
hireai-controller     controller/base, controller/config, controller/biz/<route-group>
hireai-main           HireAiApplication, application.yml, db/migration, integration and most unit tests
```

Layer-local unit tests also live in `hireai-domain`, `hireai-application`, `hireai-infrastructure` and `hireai-utility`.

**Grouping:** domain, application and repository code is grouped by subdomain: `identity`, `ledger`, `offering`, `task`, `reputation`, `adjudication`. `admin` is an extra read-side group in application and repository. Controllers are grouped by HTTP route, a different axis: `controller/biz/apikey` calls code under `identity`.

**Nesting:** there is no single rule; follow the neighbours. `ledger` and `offering` nest each aggregate. `identity` keeps `UserModel` flat and nests `apikey` and `webhooksubscription` ([ADR 0002](../adr/0002-apikey-webhook-nest-under-identity-and-task.md)). `task` keeps `TaskModel` flat and nests `idempotency`, `webhookdelivery`, `dispatchattempt` and `routing`. `adjudication` and `reputation` are flat, with their two aggregates side by side.

## Naming

| Kind | Pattern | Example |
|---|---|---|
| Aggregate or child entity | `XxxModel` | `TaskModel` (value objects have no suffix: `OutputSpec`, `Ruling`) |
| Persistence entity | `XxxDO` | `AgentDO` |
| Repository | `XxxRepository`, `XxxRepositoryImpl` | `WalletRepository` |
| Domain service | Noun + verb | `TaskSubmitDomainService`, `WalletFreezeDomainService` |
| Domain event | `XxxDomainEvent` | `TaskSubmittedDomainEvent` |
| Inbound carrier | `XxxInfo` | `TaskSubmitInfo` |
| App service | `XxxReadAppService`, `XxxWriteAppService`, or use-case named | `TaskReviewAppService`, `RoutingAppService` |
| Web | `XxxController`, `XxxRequest`, `XxxDTO`, `Xxx2DTOConverter` | `Dispute2DTOConverter` |
| Outbound client | `XxxClient` | `AgentDispatchClient` |

## Rules

1. **Reach children through the root**, and don't import another aggregate's internals. The allowed cross-subdomain imports in `domain/biz` are shared types only:
   - `adjudication` uses `task.model.OutputSpec`, `task.model.TaskResultModel`, `task.enums.RejectReason`.
   - `offering` uses `task.model.OutputSpec`, `reputation.info.ReputationAggregates`.
   - `task` uses `offering.agent.info.AgentCandidate`.

   Add to this list deliberately. Anything else goes through an app service.
2. **One repository per aggregate root**, and depend on the interface. Exceptions: `OAuthIdentityRepository` serves a child of `UserModel`, and `ApiKeyTaskRepository` has no aggregate.
3. **App services are interface + `impl/`.** Use `XxxReadAppService`/`XxxWriteAppService` when a subdomain splits cleanly and a use-case name when a flow spans both. Read services return models; write services return an ID, `void`, or a small result.
4. **Keep behaviour in the aggregate.** Most transitions are `TaskModel` methods called from app services. Add a domain service when a rule spans objects or needs a pure calculation. Don't build a god-class.
5. **Cross-aggregate flows are direct app-service calls.** The only domain events are `TaskSubmittedDomainEvent`, `AgentRegisteredDomainEvent` and `AgentActivatedDomainEvent`. Routing is not an aggregate: `RoutingAppService` coordinates Task and Agent.
6. **Controllers stay thin.** Validate, build the `Info`, call, convert, wrap.

The aggregate, child and repository map is in [`data-model.md`](data-model.md).
