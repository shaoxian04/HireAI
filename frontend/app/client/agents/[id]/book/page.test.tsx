import { describe, it, expect, beforeAll, afterAll, afterEach, vi } from "vitest";
import { render, screen, fireEvent, waitFor } from "@testing-library/react";
import { http, HttpResponse } from "msw";
import { server, ok } from "../../../../../test/msw/handlers";
import { AuthProvider } from "@/lib/auth";
import BookAgentPage from "@/app/client/agents/[id]/book/page";

vi.mock("next/navigation", () => ({
  useRouter: () => ({ push: vi.fn(), replace: vi.fn() }),
  useParams: () => ({ id: "ag-1" }),
  usePathname: () => "/client/agents/ag-1/book",
}));

beforeAll(() => server.listen({ onUnhandledRequest: "error" }));
afterEach(() => {
  server.resetHandlers();
  localStorage.clear();
});
afterAll(() => server.close());

function renderPage() {
  localStorage.setItem("hireai.token", "t");
  localStorage.setItem("hireai.auth", JSON.stringify({ userId: "u-1", role: "CLIENT" }));
  return render(
    <AuthProvider>
      <BookAgentPage />
    </AuthProvider>,
  );
}

/** Fills the form and submits, recording the Idempotency-Key of each POST. */
async function submitOnce(keys: string[], status: number) {
  server.use(
    http.post("*/api/tasks/direct", ({ request }) => {
      keys.push(request.headers.get("Idempotency-Key") ?? "");
      return status === 200
        ? ok({ id: "t-1", status: "SUBMITTED" })
        : HttpResponse.json(
            { success: false, code: "INTERNAL_ERROR", message: "Unexpected error" },
            { status },
          );
    }),
  );
  fireEvent.click(screen.getByRole("button", { name: /book/i }));
  await waitFor(() => expect(keys.length).toBeGreaterThan(0));
}

describe("book agent — idempotency", () => {
  it("sends an Idempotency-Key when booking", async () => {
    const keys: string[] = [];
    renderPage();
    fireEvent.change(await screen.findByLabelText(/title/i), {
      target: { value: "Summarise" },
    });
    fireEvent.change(screen.getByLabelText(/description/i), { target: { value: "the report" } });
    await submitOnce(keys, 200);
    expect(keys[0]).toBeTruthy();
  });

  it("reuses the same key when retrying an unchanged booking", async () => {
    // Booking freezes escrow, so a retry after a post-commit failure must resolve to the
    // original task rather than booking (and freezing) a second time.
    const keys: string[] = [];
    renderPage();
    fireEvent.change(await screen.findByLabelText(/title/i), {
      target: { value: "Summarise" },
    });
    fireEvent.change(screen.getByLabelText(/description/i), { target: { value: "the report" } });
    await submitOnce(keys, 500);
    await screen.findByRole("alert");

    fireEvent.click(screen.getByRole("button", { name: /book/i }));
    await waitFor(() => expect(keys).toHaveLength(2));
    expect(keys[0]).toBeTruthy(); // else two *absent* headers would trivially match
    expect(keys[1]).toBe(keys[0]);
  });

  it("issues a new key once the payload changes", async () => {
    const keys: string[] = [];
    renderPage();
    fireEvent.change(await screen.findByLabelText(/title/i), {
      target: { value: "Summarise" },
    });
    fireEvent.change(screen.getByLabelText(/description/i), { target: { value: "the report" } });
    await submitOnce(keys, 500);
    await screen.findByRole("alert");

    fireEvent.change(screen.getByLabelText(/title/i), { target: { value: "Summarise v2" } });
    fireEvent.click(screen.getByRole("button", { name: /book/i }));
    await waitFor(() => expect(keys).toHaveLength(2));
    expect(keys[1]).not.toBe(keys[0]);
  });
});
