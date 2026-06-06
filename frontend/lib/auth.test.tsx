import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { act, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { AuthProvider, useAuth } from "./auth";
import { TOKEN_KEY } from "./api";

// userEvent ships with testing-library/react in recent versions; if absent, swap for fireEvent.

function Harness() {
  const { token, role, userId, login, logout } = useAuth();
  return (
    <div>
      <span data-testid="token">{token ?? "none"}</span>
      <span data-testid="role">{role ?? "none"}</span>
      <span data-testid="userId">{userId ?? "none"}</span>
      <button onClick={() => login("a@b.c", "pw")}>login</button>
      <button onClick={() => logout()}>logout</button>
    </div>
  );
}

const loginOk = () =>
  new Response(
    JSON.stringify({ success: true, code: "OK", message: "", data: { token: "jwt-123", userId: "u1", role: "CLIENT" } }),
    { status: 200 },
  );

describe("AuthProvider / useAuth", () => {
  beforeEach(() => {
    localStorage.clear();
    vi.restoreAllMocks();
  });
  afterEach(() => vi.unstubAllGlobals());

  it("starts unauthenticated", () => {
    render(<AuthProvider><Harness /></AuthProvider>);
    expect(screen.getByTestId("token").textContent).toBe("none");
  });

  it("login() stores token + session and exposes identity", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(loginOk()));
    render(<AuthProvider><Harness /></AuthProvider>);

    await userEvent.click(screen.getByText("login"));

    await waitFor(() => expect(screen.getByTestId("token").textContent).toBe("jwt-123"));
    expect(screen.getByTestId("role").textContent).toBe("CLIENT");
    expect(screen.getByTestId("userId").textContent).toBe("u1");
    expect(localStorage.getItem(TOKEN_KEY)).toBe("jwt-123");
  });

  it("logout() clears token + session", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(loginOk()));
    render(<AuthProvider><Harness /></AuthProvider>);
    await userEvent.click(screen.getByText("login"));
    await waitFor(() => expect(screen.getByTestId("token").textContent).toBe("jwt-123"));

    await act(async () => { await userEvent.click(screen.getByText("logout")); });

    expect(screen.getByTestId("token").textContent).toBe("none");
    expect(localStorage.getItem(TOKEN_KEY)).toBeNull();
  });

  it("rehydrates a persisted session on mount", () => {
    localStorage.setItem(TOKEN_KEY, "jwt-xyz");
    localStorage.setItem("hireai.auth", JSON.stringify({ userId: "u9", role: "BUILDER" }));
    render(<AuthProvider><Harness /></AuthProvider>);
    expect(screen.getByTestId("token").textContent).toBe("jwt-xyz");
    expect(screen.getByTestId("role").textContent).toBe("BUILDER");
  });
});
