import { describe, it, expect, vi, beforeEach } from "vitest";
import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { SigningSecretPanel } from "@/components/manage/SigningSecretPanel";

const apiMock = vi.fn();
vi.mock("@/lib/api", async () => {
  const actual = await vi.importActual<typeof import("@/lib/api")>("@/lib/api");
  return { ...actual, api: (path: string, init?: RequestInit) => apiMock(path, init) };
});

describe("SigningSecretPanel", () => {
  beforeEach(() => apiMock.mockReset());

  it("never shows a stored secret, and rotates only after confirmation, revealing the new one once", async () => {
    apiMock.mockResolvedValue({ agentId: "a-1", signingSecret: "agsec_new" });
    const onIssued = vi.fn();
    render(<SigningSecretPanel agentId="a-1" hasSigningSecret onIssued={onIssued} />);

    expect(screen.queryByText(/agsec_/)).not.toBeInTheDocument();
    await userEvent.click(screen.getByRole("button", { name: /rotate secret/i }));
    expect(apiMock).not.toHaveBeenCalled();

    const dialog = await screen.findByRole("dialog", { name: /rotate the signing secret/i });
    await userEvent.click(within(dialog).getByRole("button", { name: /rotate secret/i }));

    expect(apiMock).toHaveBeenCalledWith("/agents/a-1/rotate-secret", { method: "POST" });
    expect(await screen.findByText("agsec_new")).toBeInTheDocument();
    expect(onIssued).toHaveBeenCalled();

    await userEvent.click(screen.getByRole("button", { name: /done/i }));
    expect(screen.queryByText("agsec_new")).not.toBeInTheDocument();
  });

  it("prompts an agent with no secret to get one", async () => {
    apiMock.mockResolvedValue({ agentId: "a-1", signingSecret: "agsec_first" });
    render(<SigningSecretPanel agentId="a-1" hasSigningSecret={false} onIssued={() => {}} />);

    expect(screen.getByText(/no signing secret yet/i)).toBeInTheDocument();
    await userEvent.click(screen.getByRole("button", { name: /get a signing secret/i }));

    expect(apiMock).toHaveBeenCalledWith("/agents/a-1/rotate-secret", { method: "POST" });
    expect(await screen.findByText("agsec_first")).toBeInTheDocument();
  });
});
