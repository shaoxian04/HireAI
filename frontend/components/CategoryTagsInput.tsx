"use client";

import { useEffect, useId, useState, type KeyboardEvent } from "react";
import { api } from "@/lib/api";
import type { CategoryCountDTO } from "@/lib/types";

/** Splits the comma-separated value into committed tags and the trailing draft being typed. */
function parse(csv: string): { tags: string[]; draft: string } {
  const parts = csv.split(",");
  const draft = parts.pop() ?? "";
  const tags: string[] = [];
  for (const raw of parts) {
    const t = raw.trim().toLowerCase();
    if (t && !tags.includes(t)) tags.push(t);
  }
  return { tags, draft: draft.trimStart() };
}

function join(tags: string[], draft: string): string {
  return tags.length ? `${tags.join(", ")}, ${draft}` : draft;
}

/**
 * Capability categories as removable tags, with the categories other agents already serve offered
 * as suggestions, so a builder picks "summarisation" rather than inventing "summarizing". New
 * categories are still allowed. The value stays a comma-separated string, the same shape the
 * request body is built from.
 */
export function CategoryTagsInput({
  id,
  value,
  onChange,
  required,
}: {
  id: string;
  value: string;
  onChange: (csv: string) => void;
  required?: boolean;
}) {
  const listId = useId();
  const hintId = `${id}-tags-hint`;
  const [known, setKnown] = useState<string[]>([]);
  const { tags, draft } = parse(value);

  useEffect(() => {
    let live = true;
    api<CategoryCountDTO[]>("/catalogue/categories")
      .then((cs) => live && setKnown(cs.map((c) => c.category)))
      .catch(() => {
        /* suggestions are a nicety; free typing still works */
      });
    return () => {
      live = false;
    };
  }, []);

  function commit(next: string) {
    const t = next.trim().toLowerCase();
    if (!t || tags.includes(t)) {
      onChange(join(tags, ""));
      return;
    }
    onChange(join([...tags, t], ""));
  }

  function remove(tag: string) {
    onChange(join(tags.filter((t) => t !== tag), draft));
  }

  function onKeyDown(e: KeyboardEvent<HTMLInputElement>) {
    if ((e.key === "Enter" || e.key === ",") && draft.trim()) {
      e.preventDefault();
      commit(draft);
    } else if (e.key === "Backspace" && !draft && tags.length) {
      e.preventDefault();
      remove(tags[tags.length - 1]);
    }
  }

  return (
    <div>
      <div className="flex min-h-10 flex-wrap items-center gap-1.5 rounded-md border border-line-bright bg-surface px-2 py-1.5 transition focus-within:border-accent/70 focus-within:ring-2 focus-within:ring-accent/20">
        {tags.map((t) => (
          <span
            key={t}
            className="inline-flex items-center gap-1 rounded bg-accent/10 py-0.5 pl-2 pr-1 text-[0.8125rem] font-medium text-accent"
          >
            {t}
            <button
              type="button"
              onClick={() => remove(t)}
              aria-label={`Remove ${t}`}
              className="grid size-5 place-items-center rounded text-accent/80 hover:bg-accent/15 hover:text-accent"
            >
              <svg viewBox="0 0 10 10" className="size-2.5" aria-hidden>
                <path d="M2 2l6 6M8 2 2 8" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" />
              </svg>
            </button>
          </span>
        ))}
        <input
          id={id}
          list={listId}
          value={draft}
          // A typed or pasted comma splits naturally: everything before it becomes a tag.
          onChange={(e) => onChange(join(tags, e.target.value))}
          onKeyDown={onKeyDown}
          onBlur={() => draft.trim() && commit(draft)}
          placeholder={tags.length ? "Add another" : "e.g. summarisation"}
          required={required && tags.length === 0}
          aria-describedby={hintId}
          // the wrapper draws the focus ring for the whole field
          style={{ outline: "none" }}
          className="h-7 min-w-[8rem] flex-1 appearance-none border-0 bg-transparent px-1 text-sm text-fg shadow-none outline-none ring-0 placeholder:text-dim focus:outline-none focus-visible:outline-none [&::-webkit-calendar-picker-indicator]:hidden"
        />
      </div>
      <datalist id={listId}>
        {known
          .filter((k) => !tags.includes(k))
          .map((k) => (
            <option key={k} value={k} />
          ))}
      </datalist>
      <p id={hintId} className="mt-1.5 text-xs text-dim">
        Press Enter or comma to add. Pick an existing category where one fits, so clients filtering by it find your agent.
      </p>
    </div>
  );
}
