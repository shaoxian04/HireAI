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

  it("traps Tab focus within the dialog", () => {
    render(
      <Modal open onClose={() => {}} ariaLabel="Trap">
        <button>first</button>
        <button>last</button>
      </Modal>,
    );
    const first = screen.getByRole("button", { name: "first" });
    const last = screen.getByRole("button", { name: "last" });
    last.focus();
    fireEvent.keyDown(document, { key: "Tab" });
    expect(document.activeElement).toBe(first);
    first.focus();
    fireEvent.keyDown(document, { key: "Tab", shiftKey: true });
    expect(document.activeElement).toBe(last);
  });

  it("restores focus to the trigger on close", () => {
    function Harness({ open }: { open: boolean }) {
      return (
        <>
          <button data-testid="trigger">open</button>
          <Modal open={open} onClose={() => {}} ariaLabel="Restore"><button>inside</button></Modal>
        </>
      );
    }
    const { rerender } = render(<Harness open={false} />);
    const trigger = screen.getByTestId("trigger");
    trigger.focus();
    expect(document.activeElement).toBe(trigger);
    rerender(<Harness open />);
    expect(document.activeElement).not.toBe(trigger);
    rerender(<Harness open={false} />);
    expect(document.activeElement).toBe(trigger);
  });

  it("does not rebuild the focus trap when onClose identity changes while open", () => {
    const { rerender } = render(
      <Modal open onClose={() => {}} ariaLabel="Stable"><button>only</button></Modal>,
    );
    const only = screen.getByRole("button", { name: "only" });
    only.focus();
    // Parent re-renders with a brand-new onClose reference (the inline-arrow pattern).
    rerender(<Modal open onClose={() => {}} ariaLabel="Stable"><button>only</button></Modal>);
    // Keyed on [open] only, the effect does not re-run, so focus stays where the user left it.
    // (With the old [open, onClose] deps the rerun would steal focus back to the dialog.)
    expect(document.activeElement).toBe(only);
  });

  it("includes textarea in the focus trap (expanded selector)", () => {
    render(
      <Modal open onClose={() => {}} ariaLabel="Wide">
        <button>first</button>
        <textarea aria-label="notes" />
      </Modal>,
    );
    const first = screen.getByRole("button", { name: "first" });
    const notes = screen.getByLabelText("notes");
    notes.focus();
    // textarea is the last trap-eligible node; Tab must wrap back to the first, not escape.
    fireEvent.keyDown(document, { key: "Tab" });
    expect(document.activeElement).toBe(first);
  });
});
