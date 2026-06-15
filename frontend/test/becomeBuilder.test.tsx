import { describe, it, expect, beforeAll, afterAll, afterEach, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { server } from "./msw/handlers";
import { AuthProvider } from "@/lib/auth";
import BecomeBuilderPage from "@/app/client/become-builder/page";

const replace = vi.fn();
vi.mock("next/navigation", () => ({ useRouter: () => ({ replace, push: vi.fn() }) }));

beforeAll(() => server.listen({ onUnhandledRequest: "error" }));
afterEach(() => {
  server.resetHandlers();
  localStorage.clear();
  replace.mockClear();
});
afterAll(() => server.close());

function renderPage() {
  localStorage.setItem("hireai.token", "t");
  localStorage.setItem("hireai.auth", JSON.stringify({ userId: "u-1", roles: ["CLIENT"] }));
  return render(<AuthProvider><BecomeBuilderPage /></AuthProvider>);
}

describe("become-builder", () => {
  it("upgrades to builder after accepting terms and routes to /builder", async () => {
    renderPage();
    await userEvent.click(screen.getByLabelText(/accept/i));
    await userEvent.click(screen.getByRole("button", { name: /become a builder/i }));
    await vi.waitFor(() => expect(replace).toHaveBeenCalledWith("/builder"));
    expect(localStorage.getItem("hireai.token")).toBe("expanded-jwt");
  });

  it("keeps the button disabled until terms are accepted", () => {
    renderPage();
    expect(screen.getByRole("button", { name: /become a builder/i })).toBeDisabled();
  });
});
