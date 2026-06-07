import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import { AuthProvider } from "@/lib/auth";
import { TOKEN_KEY } from "@/lib/api";
import { RequireAuth } from "./RequireAuth";

const replace = vi.fn();
vi.mock("next/navigation", () => ({ useRouter: () => ({ replace, push: vi.fn() }) }));

describe("RequireAuth", () => {
  beforeEach(() => {
    localStorage.clear();
    replace.mockClear();
  });
  afterEach(() => vi.restoreAllMocks());

  it("redirects to /login when there is no token", async () => {
    render(
      <AuthProvider>
        <RequireAuth><div>secret</div></RequireAuth>
      </AuthProvider>,
    );
    await waitFor(() => expect(replace).toHaveBeenCalledWith("/login"));
    expect(screen.queryByText("secret")).not.toBeInTheDocument();
  });

  it("renders children when authenticated", async () => {
    localStorage.setItem(TOKEN_KEY, "jwt-123");
    localStorage.setItem("hireai.auth", JSON.stringify({ userId: "u1", role: "CLIENT" }));
    render(
      <AuthProvider>
        <RequireAuth><div>secret</div></RequireAuth>
      </AuthProvider>,
    );
    await waitFor(() => expect(screen.getByText("secret")).toBeInTheDocument());
    expect(replace).not.toHaveBeenCalled();
  });

  it("redirects to /login when the role does not match", async () => {
    localStorage.setItem(TOKEN_KEY, "jwt-123");
    localStorage.setItem("hireai.auth", JSON.stringify({ userId: "u1", role: "CLIENT" }));
    render(
      <AuthProvider>
        <RequireAuth role="BUILDER"><div>builder-only</div></RequireAuth>
      </AuthProvider>,
    );
    await waitFor(() => expect(replace).toHaveBeenCalledWith("/login"));
    expect(screen.queryByText("builder-only")).not.toBeInTheDocument();
  });
});
