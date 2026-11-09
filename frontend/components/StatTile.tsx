/** One figure in a stat strip: small label above a monospaced value. Tone colours the value only when it carries meaning. */
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
    <div className="bg-surface px-5 py-4">
      <p className="text-xs text-dim">{label}</p>
      <p className={`display tabular mt-1 text-[2.4rem] ${toneCls}`}>{value}</p>
    </div>
  );
}
