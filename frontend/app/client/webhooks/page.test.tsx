import { describe, it, expect, beforeEach, afterEach, vi } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { setupServer } from "msw/node";
import { AuthProvider } from "@/lib/auth";
import Page from "./page";

vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace: vi.fn(), push: vi.fn() }),
  useParams: () => ({}),
}));

const server = setupServer(
  http.get("/api/keys", () =>
    HttpResponse.json({
      success: true, code: "OK", message: null,
      data: [{ id: "k1", name: "prod-bot", displayPrefix: "hk_live_aaa111",
               spendCap: null, dailySpendCap: null, status: "ACTIVE",
               lastUsedAt: null, createdAt: "2026-07-15T10:00:00Z" }],
    }),
  ),
  http.get("/api/webhooks/subscription", () =>
    HttpResponse.json(
      { success: false, code: "NOT_FOUND", message: "No subscription", data: null },
      { status: 404 },
    ),
  ),
  http.post("/api/webhooks/subscription", () =>
    HttpResponse.json({
      success: true, code: "OK", message: null,
      data: { id: "sub1", apiKeyId: "k1", callbackUrl: "https://c.example.com/cb",
              signingSecret: "whsec_shown", active: true,
              createdAt: "2026-07-20T00:00:00Z", updatedAt: "2026-07-20T00:00:00Z" },
    }),
  ),
  http.get("/api/webhooks/deliveries", () =>
    HttpResponse.json({ success: true, code: "OK", message: null, data: [] }),
  ),
);

beforeEach(() => {
  localStorage.setItem("hireai.token", "jwt");
  localStorage.setItem("hireai.auth", JSON.stringify({ userId: "u1", roles: ["CLIENT"] }));
  server.listen();
});
afterEach(() => { server.resetHandlers(); server.close(); localStorage.clear(); });

function renderPage() {
  return render(
    <AuthProvider>
      <Page />
    </AuthProvider>,
  );
}

describe("WebhooksPage", () => {
  it("registers a callback and reveals the signing secret", async () => {
    const user = userEvent.setup();
    renderPage();

    // wait for the key selector to load before interacting with the form
    await screen.findByRole("option", { name: /prod-bot/i });

    await user.type(screen.getByLabelText(/callback url/i), "https://c.example.com/cb");
    await user.click(screen.getByRole("button", { name: /register|save/i }));

    await waitFor(() => expect(screen.getByText(/whsec_shown/)).toBeInTheDocument());
  });

  it("renders the delivery log with a Resend button for a failed row", async () => {
    server.use(
      http.get("/api/webhooks/deliveries", () =>
        HttpResponse.json({
          success: true, code: "OK", message: null,
          data: [{ eventId: "e1", taskId: "t1", eventType: "task.failed", status: "DEAD",
                   attempts: 28, nextAttemptAt: "2026-07-20T00:00:00Z",
                   createdAt: "2026-07-19T00:00:00Z", deliveredAt: null,
                   lastError: "HTTP 503: connect timeout" }],
        }),
      ),
    );

    renderPage();

    await waitFor(() => expect(screen.getByText("DEAD")).toBeInTheDocument());
    expect(screen.getByRole("button", { name: /resend/i })).toBeInTheDocument();
  });

  it("clicking Resend redelivers a DEAD row and reflects PENDING once the log reloads", async () => {
    const user = userEvent.setup();
    let redeliverCalled = false;
    let rowStatus: "DEAD" | "PENDING" = "DEAD";

    server.use(
      http.get("/api/webhooks/deliveries", () =>
        HttpResponse.json({
          success: true, code: "OK", message: null,
          data: [{ eventId: "e1", taskId: "t1", eventType: "task.failed", status: rowStatus,
                   attempts: rowStatus === "DEAD" ? 28 : 29, nextAttemptAt: "2026-07-20T00:00:00Z",
                   createdAt: "2026-07-19T00:00:00Z", deliveredAt: null,
                   lastError: rowStatus === "DEAD" ? "HTTP 503: connect timeout" : null }],
        }),
      ),
      http.post("/api/webhooks/deliveries/e1/redeliver", () => {
        redeliverCalled = true;
        rowStatus = "PENDING";
        return HttpResponse.json({ success: true, code: "OK", message: null, data: null });
      }),
    );

    renderPage();

    await waitFor(() => expect(screen.getByText("DEAD")).toBeInTheDocument());
    await user.click(screen.getByRole("button", { name: /resend/i }));

    await waitFor(() => expect(redeliverCalled).toBe(true));
    await waitFor(() => expect(screen.getByText("PENDING")).toBeInTheDocument());
    expect(screen.queryByText("DEAD")).not.toBeInTheDocument();
  });

  it("the DEAD health banner survives an unrelated status filter (account-scoped, not filter-scoped)", async () => {
    const user = userEvent.setup();

    server.use(
      http.get("/api/webhooks/deliveries", ({ request }) => {
        const url = new URL(request.url);
        const status = url.searchParams.get("status");
        if (status === "PENDING") {
          return HttpResponse.json({ success: true, code: "OK", message: null, data: [] });
        }
        return HttpResponse.json({
          success: true, code: "OK", message: null,
          data: [{ eventId: "e1", taskId: "t1", eventType: "task.failed", status: "DEAD",
                   attempts: 28, nextAttemptAt: "2026-07-20T00:00:00Z",
                   createdAt: "2026-07-19T00:00:00Z", deliveredAt: null,
                   lastError: "HTTP 503: connect timeout" }],
        });
      }),
    );

    renderPage();

    await waitFor(() => expect(screen.getByText(/exhausted retries/i)).toBeInTheDocument());

    // Filtering the table to "Pending" empties the visible rows but must NOT hide the banner —
    // the account still has a DEAD delivery, just not one matching this filter.
    await user.selectOptions(screen.getByLabelText(/status/i), "PENDING");

    await waitFor(() => expect(screen.getByText(/no deliveries yet/i)).toBeInTheDocument());
    expect(screen.getByText(/exhausted retries/i)).toBeInTheDocument();
  });

  it("rotate secret posts to rotate-secret and reveals the new secret", async () => {
    const user = userEvent.setup();
    let rotateCalled = false;

    server.use(
      http.get("/api/webhooks/subscription", () =>
        HttpResponse.json({
          success: true, code: "OK", message: null,
          data: { id: "sub1", apiKeyId: "k1", callbackUrl: "https://c.example.com/cb",
                  signingSecret: "whsec_old", active: true,
                  createdAt: "2026-07-15T00:00:00Z", updatedAt: "2026-07-15T00:00:00Z" },
        }),
      ),
      http.post("/api/webhooks/subscription/rotate-secret", () => {
        rotateCalled = true;
        return HttpResponse.json({
          success: true, code: "OK", message: null,
          data: { id: "sub1", apiKeyId: "k1", callbackUrl: "https://c.example.com/cb",
                  signingSecret: "whsec_new", active: true,
                  createdAt: "2026-07-15T00:00:00Z", updatedAt: "2026-07-20T00:00:00Z" },
        });
      }),
    );

    renderPage();

    await screen.findByText(/whsec_old/);
    await user.click(screen.getByRole("button", { name: /rotate secret/i }));

    await waitFor(() => expect(rotateCalled).toBe(true));
    await waitFor(() => expect(screen.getByText(/whsec_new/)).toBeInTheDocument());
    expect(screen.queryByText(/whsec_old/)).not.toBeInTheDocument();
  });

  it("deactivate posts to deactivate and the subscription shows Inactive", async () => {
    const user = userEvent.setup();
    let deactivateCalled = false;
    let active = true;

    server.use(
      http.get("/api/webhooks/subscription", () =>
        HttpResponse.json({
          success: true, code: "OK", message: null,
          data: { id: "sub1", apiKeyId: "k1", callbackUrl: "https://c.example.com/cb",
                  signingSecret: "whsec_shown", active,
                  createdAt: "2026-07-15T00:00:00Z", updatedAt: "2026-07-15T00:00:00Z" },
        }),
      ),
      http.post("/api/webhooks/subscription/deactivate", () => {
        deactivateCalled = true;
        active = false;
        return HttpResponse.json({ success: true, code: "OK", message: null, data: null });
      }),
    );

    renderPage();

    await screen.findByText("Active");
    await user.click(screen.getByRole("button", { name: /^deactivate$/i }));

    await waitFor(() => expect(deactivateCalled).toBe(true));
    await waitFor(() => expect(screen.getByText("Inactive")).toBeInTheDocument());
    expect(screen.queryByRole("button", { name: /^deactivate$/i })).not.toBeInTheDocument();
  });
});
