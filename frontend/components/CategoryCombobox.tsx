"use client";

import { useEffect, useId, useRef, useState, type KeyboardEvent } from "react";
import { api } from "@/lib/api";
import type { CategoryCountDTO } from "@/lib/types";

interface Props {
  value: string;
  onChange: (value: string) => void;
  id?: string;
}

const INPUT_CLS =
  "block w-full rounded-md border border-line bg-surface-2 px-3 py-2 font-mono text-sm text-fg shadow-inner transition placeholder:text-dim focus:border-accent focus:outline-none focus:ring-2 focus:ring-accent/25";

/**
 * Strict, searchable category picker. Options come from GET /catalogue/categories. `onChange` is
 * only ever called with a REAL category or "" — so a non-empty value is guaranteed valid and the
 * parent can gate submission on `!!value`. Falls back to a plain text input if the fetch fails.
 */
export function CategoryCombobox({ value, onChange, id }: Props) {
  const [categories, setCategories] = useState<CategoryCountDTO[]>([]);
  const [failed, setFailed] = useState(false);
  const [query, setQuery] = useState(value);
  const [open, setOpen] = useState(false);
  const [highlight, setHighlight] = useState(0);
  const rootRef = useRef<HTMLDivElement>(null);
  const listId = useId();

  useEffect(() => {
    let cancelled = false;
    api<CategoryCountDTO[]>("/catalogue/categories")
      .then((c) => { if (!cancelled) setCategories(c); })
      .catch(() => { if (!cancelled) setFailed(true); });
    return () => { cancelled = true; };
  }, []);

  // Reflect an external commit (e.g. a restored draft) into the visible text. `value` is only ever
  // a real category or "" (this component guarantees it), so a non-empty value is safe to display.
  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect -- one-way sync of an external committed value into local display text; guarded so it never fights user typing
    if (value && value !== query) setQuery(value);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [value]);

  useEffect(() => {
    function onDoc(e: MouseEvent) {
      if (rootRef.current && !rootRef.current.contains(e.target as Node)) setOpen(false);
    }
    document.addEventListener("mousedown", onDoc);
    return () => document.removeEventListener("mousedown", onDoc);
  }, []);

  if (failed) {
    return (
      <input
        id={id}
        className={INPUT_CLS}
        value={value}
        onChange={(e) => onChange(e.target.value)}
        placeholder="category"
      />
    );
  }

  const filtered = categories.filter((c) =>
    c.category.toLowerCase().includes(query.trim().toLowerCase()),
  );

  function commit(cat: string) {
    setQuery(cat);
    onChange(cat);
    setOpen(false);
  }

  function onInput(text: string) {
    setQuery(text);
    setOpen(true);
    setHighlight(0);
    if (value) onChange(""); // strict: editing invalidates any committed category
  }

  function onKeyDown(e: KeyboardEvent) {
    if (e.key === "ArrowDown") {
      e.preventDefault();
      setOpen(true);
      setHighlight((h) => Math.min(h + 1, filtered.length - 1));
    } else if (e.key === "ArrowUp") {
      e.preventDefault();
      setHighlight((h) => Math.max(h - 1, 0));
    } else if (e.key === "Enter") {
      if (open && filtered[highlight]) {
        e.preventDefault();
        commit(filtered[highlight].category);
      }
    } else if (e.key === "Escape") {
      setOpen(false);
    }
  }

  return (
    <div ref={rootRef} className="relative">
      <input
        id={id}
        role="combobox"
        aria-expanded={open}
        aria-controls={listId}
        aria-autocomplete="list"
        className={INPUT_CLS}
        value={query}
        onChange={(e) => onInput(e.target.value)}
        onFocus={() => setOpen(true)}
        onKeyDown={onKeyDown}
        placeholder="search categories…"
      />
      {open && (
        <ul
          id={listId}
          role="listbox"
          className="absolute z-20 mt-2 max-h-64 w-full overflow-auto rounded-md border border-line-bright bg-surface shadow-xl"
        >
          {filtered.length === 0 ? (
            <li className="px-3.5 py-3 font-mono text-xs text-dim">
              No matching category — try the marketplace.
            </li>
          ) : (
            filtered.map((c, i) => (
              <li
                key={c.category}
                role="option"
                aria-selected={i === highlight}
                onMouseDown={(e) => { e.preventDefault(); commit(c.category); }}
                onMouseEnter={() => setHighlight(i)}
                className={`flex cursor-pointer items-center justify-between gap-3 border-b border-line px-3.5 py-2.5 last:border-b-0 ${
                  i === highlight ? "bg-surface-2" : ""
                }`}
              >
                <span className="font-medium">{c.category}</span>
                <span className="rounded-full border border-line px-2 py-0.5 font-mono text-[0.66rem] text-muted">
                  {c.agentCount} {c.agentCount === 1 ? "agent" : "agents"}
                </span>
              </li>
            ))
          )}
        </ul>
      )}
    </div>
  );
}
