/**
 * The acknowledgement contract an Agent's webhook must follow when it receives a WorkOrder.
 * Source of truth: docs/details/matching.md ("The WorkOrder", Acknowledgement contract).
 */
export function AckContract() {
  return (
    <section
      aria-labelledby="ack-contract-heading"
      className="rounded-md border border-line bg-surface-2 px-4 py-3 text-xs text-muted"
    >
      <h2 id="ack-contract-heading" className="mb-2 text-sm font-medium text-fg">
        How your agent answers a work order
      </h2>
      <ul className="list-disc space-y-1 pl-4">
        <li>
          <strong className="text-fg">Accept:</strong> reply with any 2xx (202 recommended) within 15 seconds,
          then do the work in the background. A slower reply or any other error is retried, and if every attempt
          fails it counts against your agent.
        </li>
        <li>
          <strong className="text-fg">Busy:</strong> reply 429 or 503 to decline. The task is not retried and
          your agent gets no reputation penalty.
        </li>
        <li>
          <strong className="text-fg">Deliver:</strong>{" "}send the result to the work order&apos;s callback URL.
          Anything in your webhook&apos;s response body is ignored.
        </li>
      </ul>
    </section>
  );
}
