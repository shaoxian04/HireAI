"use client";

import { useEffect, useState } from "react";
import { api } from "@/lib/api";
import type { CategoryCountDTO } from "@/lib/types";
import { Input } from "@/components/ui";

export const SUGGESTION_MAX_LENGTH = 100;

/**
 * Capability categories for a Builder form: one checkbox per bucket of the platform-owned list
 * (GET /catalogue/categories), so an agent can only be tagged with categories clients route and
 * filter by. Below it, an optional "suggest a category" text field for a niche agent that picks
 * `other`; the suggestion is stored for admins and never affects matching.
 */
export function CategoryPicker({
  id,
  value,
  onChange,
  suggestion,
  onSuggestionChange,
  error,
}: {
  id: string;
  value: string[];
  onChange: (slugs: string[]) => void;
  suggestion: string;
  onSuggestionChange: (text: string) => void;
  error?: string | null;
}) {
  const [categories, setCategories] = useState<CategoryCountDTO[] | null>(null);
  const [failed, setFailed] = useState(false);
  const legendId = `${id}-legend`;
  const noteId = `${id}-note`;
  const suggestionId = `${id}-suggestion`;

  useEffect(() => {
    let live = true;
    api<CategoryCountDTO[]>("/catalogue/categories")
      .then((cs) => live && setCategories(cs))
      .catch(() => live && setFailed(true));
    return () => {
      live = false;
    };
  }, []);

  function toggle(slug: string, on: boolean) {
    onChange(on ? [...value.filter((v) => v !== slug), slug] : value.filter((v) => v !== slug));
  }

  return (
    <div className="space-y-3">
      <fieldset
        id={id}
        tabIndex={-1}
        aria-labelledby={legendId}
        aria-describedby={noteId}
        aria-invalid={error ? true : undefined}
        className="space-y-1.5 focus:outline-none"
      >
        <legend id={legendId} className="block text-sm font-medium text-fg">
          Capability categories
        </legend>
        {failed ? (
          <p className="text-sm text-red">The category list couldn&apos;t be loaded. Reload the page to try again.</p>
        ) : categories === null ? (
          <p className="text-sm text-dim">Loading categories…</p>
        ) : (
          <div className="flex flex-wrap gap-1.5">
            {categories.map((c) => {
              const checked = value.includes(c.category);
              return (
                <label
                  key={c.category}
                  className={`inline-flex cursor-pointer items-center gap-1.5 rounded-md border px-2.5 py-1 text-[0.8125rem] transition has-[:focus-visible]:ring-2 has-[:focus-visible]:ring-accent/30 ${
                    checked
                      ? "border-accent/60 bg-accent/10 font-medium text-accent"
                      : "border-line-bright bg-surface text-muted hover:border-accent/40 hover:text-fg"
                  }`}
                >
                  <input
                    type="checkbox"
                    className="sr-only"
                    checked={checked}
                    onChange={(e) => toggle(c.category, e.target.checked)}
                  />
                  {c.label ?? c.category}
                </label>
              );
            })}
          </div>
        )}
        {error ? (
          <p id={noteId} role="alert" className="text-xs font-medium text-red">
            {error}
          </p>
        ) : (
          <p id={noteId} className="text-xs text-dim">
            Pick every category your agent serves. Clients and routing use this same list.
          </p>
        )}
      </fieldset>
      <div className="space-y-1.5">
        <label htmlFor={suggestionId} className="block text-sm font-medium text-fg">
          Suggest a category (optional)
        </label>
        <Input
          id={suggestionId}
          value={suggestion}
          maxLength={SUGGESTION_MAX_LENGTH}
          onChange={(e) => onSuggestionChange(e.target.value)}
          placeholder="e.g. legal clause review"
          aria-describedby={`${suggestionId}-hint`}
        />
        <p id={`${suggestionId}-hint`} className="text-xs text-dim">
          Nothing fits? Pick Other and tell us what&apos;s missing. Suggestions shape new categories.
        </p>
      </div>
    </div>
  );
}
