import { describe, it, expect, vi, beforeAll, afterAll, afterEach } from "vitest";
import { render, screen, fireEvent } from "@testing-library/react";
import { http } from "msw";
import { server, ok, fail } from "../test/msw/handlers";
import { CategoryCombobox } from "@/components/CategoryCombobox";

beforeAll(() => server.listen({ onUnhandledRequest: "error" }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());

const CATS = [
  { category: "summarisation", agentCount: 4 },
  { category: "translation", agentCount: 2 },
];

function useCats() {
  server.use(http.get("*/api/catalogue/categories", () => ok(CATS)));
}

describe("CategoryCombobox", () => {
  it("opens on focus and lists categories with counts", async () => {
    useCats();
    render(<CategoryCombobox value="" onChange={vi.fn()} />);
    fireEvent.focus(screen.getByRole("combobox"));
    expect(await screen.findByRole("option", { name: /summarisation/i })).toBeTruthy();
    expect(screen.getByText(/4 agents/i)).toBeTruthy();
  });

  it("filters as the user types", async () => {
    useCats();
    render(<CategoryCombobox value="" onChange={vi.fn()} />);
    const input = screen.getByRole("combobox");
    fireEvent.focus(input);
    await screen.findByRole("option", { name: /summarisation/i });
    fireEvent.change(input, { target: { value: "trans" } });
    expect(screen.queryByRole("option", { name: /summarisation/i })).toBeNull();
    expect(screen.getByRole("option", { name: /translation/i })).toBeTruthy();
  });

  it("commits the real category on click", async () => {
    useCats();
    const onChange = vi.fn();
    render(<CategoryCombobox value="" onChange={onChange} />);
    fireEvent.focus(screen.getByRole("combobox"));
    fireEvent.mouseDown(await screen.findByRole("option", { name: /translation/i }));
    expect(onChange).toHaveBeenCalledWith("translation");
  });

  it("selects the highlighted option with ArrowDown + Enter", async () => {
    useCats();
    const onChange = vi.fn();
    render(<CategoryCombobox value="" onChange={onChange} />);
    const input = screen.getByRole("combobox");
    fireEvent.focus(input);
    await screen.findByRole("option", { name: /summarisation/i });
    fireEvent.keyDown(input, { key: "ArrowDown" });
    fireEvent.keyDown(input, { key: "Enter" });
    expect(onChange).toHaveBeenCalledWith("translation");
  });

  it("invalidates the committed value while the user edits", async () => {
    useCats();
    const onChange = vi.fn();
    render(<CategoryCombobox value="summarisation" onChange={onChange} />);
    fireEvent.change(screen.getByRole("combobox"), { target: { value: "summ" } });
    expect(onChange).toHaveBeenCalledWith("");
  });

  it("shows an empty hint when nothing matches", async () => {
    useCats();
    render(<CategoryCombobox value="" onChange={vi.fn()} />);
    const input = screen.getByRole("combobox");
    fireEvent.focus(input);
    await screen.findByRole("option", { name: /summarisation/i });
    fireEvent.change(input, { target: { value: "zzz" } });
    expect(screen.getByText(/no matching category/i)).toBeTruthy();
  });

  it("falls back to a plain input when the categories fetch fails", async () => {
    server.use(http.get("*/api/catalogue/categories", () => fail("INTERNAL_ERROR", "boom", 500)));
    const onChange = vi.fn();
    render(<CategoryCombobox value="" onChange={onChange} />);
    const input = await screen.findByPlaceholderText("category");
    fireEvent.change(input, { target: { value: "anything" } });
    expect(onChange).toHaveBeenCalledWith("anything");
  });
});
