import type { CategoryCountDTO } from "@/lib/types";

/** Horizontal category chips; `active=""` means All. */
export function CategoryBar({
  categories,
  active,
  onSelect,
}: {
  categories: CategoryCountDTO[];
  active: string;
  onSelect: (category: string) => void;
}) {
  const chip = (label: string, value: string, count?: number) => (
    <button
      key={value || "all"}
      type="button"
      onClick={() => onSelect(value)}
      aria-pressed={active === value}
      className={`shrink-0 rounded-md border px-3 py-1.5 font-mono text-[0.68rem] uppercase tracking-wider transition ${
        active === value
          ? "border-accent/60 bg-accent/15 text-accent"
          : "border-line bg-surface-2 text-muted hover:border-line-bright hover:text-fg"
      }`}
    >
      {label}
      {count != null && <span className="ml-1.5 text-dim">{count}</span>}
    </button>
  );
  return (
    <div className="flex gap-2 overflow-x-auto pb-1" aria-label="Browse by category">
      {chip("All", "")}
      {categories.map((c) => chip(c.category, c.category, c.agentCount))}
    </div>
  );
}
