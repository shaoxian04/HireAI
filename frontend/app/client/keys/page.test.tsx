import { describe, it, expect, beforeEach, afterEach, vi } from "vitest";
import { render, screen } from "@testing-library/react";
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
      data: [{ id: "k1", name: "existing", displayPrefix: "hk_live_aaa111",
               spendCap: 100, dailySpendCap: null, status: "ACTIVE",
               lastUsedAt: null, createdAt: "2026-07-15T10:00:00Z" }],
    }),
  ),
  http.post("/api/keys", () =>
    HttpResponse.json({
      success: true, code: "OK", message: null,
      data: { id: "k2", name: "new-bot", displayPrefix: "hk_live_bbb222",
              spendCap: null, dailySpendCap: null, rawKey: "hk_live_THISISRAWSECRET" },
    }),
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

describe("client keys page", () => {
  it("lists existing keys by prefix", async () => {
    renderPage();
    expect(await screen.findByText("hk_live_aaa111")).toBeInTheDocument();
    expect(screen.getByText("existing")).toBeInTheDocument();
  });

  it("creates a key and reveals the raw value once in a modal", async () => {
    const user = userEvent.setup();
    renderPage();
    await screen.findByText("hk_live_aaa111");

    await user.click(screen.getByRole("button", { name: /create key/i }));
    await user.type(screen.getByLabelText(/name/i), "new-bot");
    await user.click(screen.getByRole("button", { name: /^create$/i }));

    // reveal-once modal shows the raw key + the warning
    expect(await screen.findByText("hk_live_THISISRAWSECRET")).toBeInTheDocument();
    expect(screen.getByText(/won't see it again/i)).toBeInTheDocument();
  });
});
