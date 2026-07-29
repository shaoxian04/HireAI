---
status: accepted
---

# Direct booking always charges exact list price

`DirectBookingAppServiceImpl.book()` enforces `assertAffordable(budget)`, i.e. `budget >= price`,
which still permits a client to overpay a directly-booked agent. We deliberately never exercise
that: the direct-booking UI always sends `budget = card.price`, with no field for the client to
type a different number. This matches the client's mental model ("I already know this agent's
price") and removes a confusing required field from the booking form.

The `>=` check itself is unchanged — it stays because the shortlist/open-task flow
(`client/tasks/new`) relies on the same `assertAffordable()` with a genuinely client-chosen budget
*ceiling* used to rank/filter candidate agents; only the winning agent's price is ever escrowed
there too (`budget: selected.price`). Direct booking just never surfaces a UI path to the `>` case.
