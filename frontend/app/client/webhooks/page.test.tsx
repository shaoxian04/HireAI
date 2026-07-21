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
});
