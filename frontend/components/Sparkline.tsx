/** Minimal SVG sparkline for request-trend points (scope cut: no charting lib). */
export function Sparkline({
  points,
  width = 220,
  height = 48,
}: {
  points: { day: string; count: number }[];
  width?: number;
  height?: number;
}) {
  if (points.length === 0) {
    return (
      <div className="flex min-h-24 flex-col items-center justify-center rounded-lg border border-dashed border-line-bright bg-surface px-4 py-6 text-center">
        <p className="text-sm font-semibold text-fg">No requests in the last 14 days</p>
        <p className="mt-1 text-xs text-muted">
          Each day&apos;s request count will chart here once clients book this agent.
        </p>
      </div>
    );
  }
  const max = Math.max(...points.map((p) => p.count), 1);
  const coords = points.map((p, i) => {
    const x = points.length > 1 ? (i / (points.length - 1)) * width : width / 2;
    const y = height - (p.count / max) * (height - 4) - 2;
    return { x, y, day: p.day };
  });
  return (
    <svg
      width={width}
      height={height}
      role="img"
      aria-label={`Requests per day over the last ${points.length} days: ${points.reduce((n, p) => n + p.count, 0)} in total, at most ${max} in one day`}
      className="overflow-visible"
    >
      {points.length > 1 && (
        <polyline
          points={coords.map((c) => `${c.x},${c.y}`).join(" ")}
          fill="none"
          stroke="var(--color-accent)"
          strokeWidth="2"
        />
      )}
      {coords.map((c) => (
        <circle
          key={c.day}
          cx={c.x}
          cy={c.y}
          r="2.5"
          fill="var(--color-accent)"
        />
      ))}
    </svg>
  );
}
