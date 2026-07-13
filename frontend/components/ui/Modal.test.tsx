import { describe, it, expect, vi } from "vitest";
import { render, screen, fireEvent } from "@testing-library/react";
import { Modal } from "@/components/ui/Modal";

describe("Modal", () => {
  it("renders children when open, nothing when closed", () => {
    const { rerender } = render(
      <Modal open onClose={() => {}} ariaLabel="Picker"><p>Body</p></Modal>,
    );
    expect(screen.getByRole("dialog", { name: "Picker" })).toBeTruthy();
    expect(screen.getByText("Body")).toBeTruthy();
    rerender(<Modal open={false} onClose={() => {}} ariaLabel="Picker"><p>Body</p></Modal>);
    expect(screen.queryByText("Body")).toBeNull();
  });

  it("calls onClose on Escape", () => {
    const onClose = vi.fn();
    render(<Modal open onClose={onClose} ariaLabel="Picker"><p>Body</p></Modal>);
    fireEvent.keyDown(document, { key: "Escape" });
    expect(onClose).toHaveBeenCalledTimes(1);
  });

  it("closes on backdrop click but not on content click", () => {
    const onClose = vi.fn();
    render(<Modal open onClose={onClose} ariaLabel="Picker"><p>Content</p></Modal>);
    fireEvent.click(screen.getByText("Content"));
    expect(onClose).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole("dialog").parentElement!);
    expect(onClose).toHaveBeenCalledTimes(1);
  });

  it("locks body scroll while open and restores on close", () => {
    const { rerender } = render(<Modal open onClose={() => {}} ariaLabel="P"><p>x</p></Modal>);
    expect(document.body.style.overflow).toBe("hidden");
    rerender(<Modal open={false} onClose={() => {}} ariaLabel="P"><p>x</p></Modal>);
    expect(document.body.style.overflow).toBe("");
  });
});
