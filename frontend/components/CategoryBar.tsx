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
      className={`shrink-0 rounded-full border px-3.5 py-1.5 text-[0.8125rem] font-medium transition ${
        active === value
          ? "border-accent bg-accent text-ink"
          : "border-line bg-surface text-muted hover:border-accent/50 hover:text-fg"
      }`}
    >
      {label}
      {count != null && <span className={`ml-1.5 ${active === value ? "text-ink/75" : "text-dim"}`}>{count}</span>}
    </button>
  );
  return (
    <div className="flex flex-wrap gap-2 pb-1" aria-label="Browse by category">
      {chip("All", "")}
      {categories.map((c) => chip(c.category, c.category, c.agentCount))}
    </div>
  );
}
