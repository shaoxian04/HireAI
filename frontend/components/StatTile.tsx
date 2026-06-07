export function StatTile({
  value,
  label,
  tone = "fg",
}: {
  value: string | number;
  label: string;
  tone?: "fg" | "accent" | "amber" | "red";
}) {
  const toneCls = { fg: "text-fg", accent: "text-accent", amber: "text-amber", red: "text-red" }[tone];
  return (
    <div className="bg-surface px-5 py-5">
      <p className={`tabular text-3xl font-extrabold ${toneCls}`}>{value}</p>
      <p className="mt-1 font-mono text-[0.6rem] uppercase tracking-[0.18em] text-dim">{label}</p>
    </div>
  );
}
