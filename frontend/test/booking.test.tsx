import { describe, it, expect, beforeAll, afterAll, afterEach, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { server } from "./msw/handlers";
import { AuthProvider } from "@/lib/auth";
import BookingPage from "@/app/client/agents/[id]/book/page";

const push = vi.fn();
vi.mock("next/navigation", () => ({
  useRouter: () => ({ push, replace: vi.fn() }),
  useParams: () => ({ id: "ag-1" }),
  useSearchParams: () => new URLSearchParams(),
  usePathname: () => "/client/agents/ag-1/book",
}));

beforeAll(() => server.listen({ onUnhandledRequest: "error" }));
afterEach(() => {
  server.resetHandlers();
  localStorage.clear();
  push.mockClear();
});
afterAll(() => server.close());

function renderBooking() {
  localStorage.setItem("hireai.token", "t");
  localStorage.setItem("hireai.auth", JSON.stringify({ userId: "u-1", role: "CLIENT" }));
  return render(
    <AuthProvider>
      <BookingPage />
    </AuthProvider>,
  );
}

describe("direct booking", () => {
  it("shows the agent's fixed price with no editable budget field, shows the adopted contract read-only, books at that price, redirects", async () => {
    let capturedBody: unknown = null;
    server.use(
      http.post("*/api/tasks/direct", async ({ request }) => {
        const body = (await request.json()) as { title: string; description: string; budget: number };
        capturedBody = body;
        return HttpResponse.json({
          success: true,
          code: "OK",
          message: "",
          data: {
            id: "t-direct-1",
            clientId: "u-1",
            title: body.title,
            description: body.description,
            budget: body.budget,
            status: "SUBMITTED",
            outputSpec: { format: "JSON", schema: "{}", acceptanceCriteria: "valid JSON" },
            createdAt: "2026-06-06T10:00:00Z",
          },
        });
      }),
    );

    renderBooking();
    expect(await screen.findByText(/summariser bot/i)).toBeInTheDocument();
    expect(screen.getByText(/you.ll pay/i)).toHaveTextContent(/10 cr/); // fixed price, shown not typed
    expect(screen.queryByLabelText(/budget/i)).not.toBeInTheDocument(); // no editable budget input at all
    expect(screen.getByText(/valid json/i)).toBeInTheDocument(); // adopted contract, read-only
    expect(screen.queryByLabelText(/category/i)).not.toBeInTheDocument(); // no category/spec inputs

    await userEvent.type(screen.getByLabelText(/title/i), "Summarise Q2");
    await userEvent.type(screen.getByLabelText(/description/i), "Summarise the Q2 report");
    await userEvent.click(screen.getByRole("button", { name: /book/i }));
    await vi.waitFor(() => expect(push).toHaveBeenCalledWith("/client/tasks/t-direct-1"));
    expect(capturedBody).toMatchObject({ budget: 10 }); // always the agent's price, never client-editable
  });
});
