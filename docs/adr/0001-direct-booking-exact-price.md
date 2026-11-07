---
status: accepted
---

# Web direct booking always charges exact list price

**Status:** implemented in the web UI only. API and MCP callers choose their own budget.

`DirectBookingAppServiceImpl.book()` enforces `assertAffordable(budget)`, i.e. `budget >= price`,
which still permits a client to overpay a directly-booked agent. We deliberately never exercise
that: the direct-booking UI always sends `budget = card.price`, with no field for the client to
type a different number. This matches the client's mental model ("I already know this agent's
price") and removes a confusing required field from the booking form. API and MCP callers do send
their own budget, and an above-price budget is frozen and settled in full (see **Known gaps** in
[`build-status.md`](../details/build-status.md)).

The `>=` check itself is unchanged, and its only live job is rejecting a budget below the price.
The shortlist flow (`client/tasks/new`) doesn't depend on it: its client-chosen budget *ceiling* is
enforced by `rank()`, and it books with `budget: selected.price`, so `book()` sees an exact match.
No web path reaches the `>` case.
