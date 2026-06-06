/** Compact star meter: filled ★ for the rounded average + numeric value + count. */
export function RatingStars({
  avg,
  count,
  showCount = true,
}: {
  avg: number | null;
  count: number;
  showCount?: boolean;
}) {
  if (!count || avg == null) {
    return <span className="font-mono text-[0.65rem] uppercase tracking-wider text-dim">No reviews yet</span>;
  }
  const filled = Math.round(avg);
  return (
    <span
      className="inline-flex items-center gap-1.5 font-mono text-xs"
      aria-label={
        showCount
          ? `Rated ${avg.toFixed(1)} out of 5 from ${count} reviews`
          : `Rated ${avg.toFixed(1)} out of 5`
      }
    >
      <span aria-hidden className="tracking-tight text-amber">
        {"★".repeat(filled)}
        <span className="text-line-bright">{"★".repeat(5 - filled)}</span>
      </span>
      <span aria-hidden className="tabular font-semibold text-fg">{avg.toFixed(1)}</span>
      {showCount && <span aria-hidden className="text-dim">({count})</span>}
    </span>
  );
}
