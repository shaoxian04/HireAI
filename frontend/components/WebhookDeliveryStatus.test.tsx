import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, it, expect, beforeAll, afterAll, afterEach } from "vitest";
import { http, HttpResponse } from "msw";
import { setupServer } from "msw/node";
import WebhookDeliveryStatus from "./WebhookDeliveryStatus";
import type { WebhookDeliveryDTO } from "@/lib/types";

/** WebResult envelope helper — every backend response is wrapped like this. */
function ok<T>(data: T) {
  return HttpResponse.json({ success: true, code: "OK", message: null, data });
}

const deadRow: WebhookDeliveryDTO = {
  eventId: "e1",
  taskId: "t1",
  eventType: "task.completed",
  status: "DEAD",
  attempts: 28,
  nextAttemptAt: "2026-07-20T00:00:00Z",
  createdAt: "2026-07-19T00:00:00Z",
  deliveredAt: null,
  lastError: "HTTP 503: connect timeout",
};

const deliveredRow: WebhookDeliveryDTO = {
  ...deadRow,
  status: "DELIVERED",
  attempts: 1,
  deliveredAt: "2026-07-19T00:05:00Z",
  lastError: null,
};

// Default: no deliveries — the "no webhook for this task" case tests rely on unless overridden.
const server = setupServer(http.get("*/api/webhooks/deliveries", () => ok<WebhookDeliveryDTO[]>([])));

beforeAll(() => server.listen({ onUnhandledRequest: "error" }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());

describe("WebhookDeliveryStatus", () => {
  it("renders nothing when there are no deliveries", async () => {
    const { container } = render(<WebhookDeliveryStatus taskId="t1" />);
    await waitFor(() => expect(container).toBeEmptyDOMElement());
  });

  it("shows Failed + Resend when the latest delivery is DEAD", async () => {
    server.use(http.get("*/api/webhooks/deliveries", () => ok([deadRow])));
    render(<WebhookDeliveryStatus taskId="t1" />);
    await waitFor(() => expect(screen.getByText(/failed/i)).toBeInTheDocument());
    expect(screen.getByRole("button", { name: /resend/i })).toBeInTheDocument();
  });

  it("shows Delivered with no action", async () => {
    server.use(http.get("*/api/webhooks/deliveries", () => ok([deliveredRow])));
    render(<WebhookDeliveryStatus taskId="t1" />);
    await waitFor(() => expect(screen.getByText(/delivered/i)).toBeInTheDocument());
    expect(screen.queryByRole("button", { name: /resend/i })).toBeNull();
  });

  it("picks the most recently created row when there are several", async () => {
    server.use(
      http.get("*/api/webhooks/deliveries", () =>
        ok([
          { ...deliveredRow, eventId: "old", createdAt: "2026-07-01T00:00:00Z" },
          { ...deadRow, eventId: "new", createdAt: "2026-07-19T00:00:00Z" },
        ]),
      ),
    );
    render(<WebhookDeliveryStatus taskId="t1" />);
    await waitFor(() => expect(screen.getByText(/failed/i)).toBeInTheDocument());
  });

  it("resending a DEAD delivery calls redeliver and reflects the reloaded status", async () => {
    const user = userEvent.setup();
    let redeliverCalled = false;
    let rowStatus: "DEAD" | "PENDING" = "DEAD";

    server.use(
      http.get("*/api/webhooks/deliveries", () =>
        ok([{ ...deadRow, status: rowStatus, lastError: rowStatus === "DEAD" ? deadRow.lastError : null }]),
      ),
      http.post("*/api/webhooks/deliveries/e1/redeliver", () => {
        redeliverCalled = true;
        rowStatus = "PENDING";
        return ok(null);
      }),
    );

    render(<WebhookDeliveryStatus taskId="t1" />);
    await waitFor(() => expect(screen.getByText(/failed/i)).toBeInTheDocument());

    await user.click(screen.getByRole("button", { name: /resend/i }));

    await waitFor(() => expect(redeliverCalled).toBe(true));
    await waitFor(() => expect(screen.getByText(/pending/i)).toBeInTheDocument());
    expect(screen.queryByRole("button", { name: /resend/i })).toBeNull();
  });
});
