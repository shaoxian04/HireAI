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
    return <p className="font-mono text-xs text-dim">No requests in the window.</p>;
  }
  const max = Math.max(...points.map((p) => p.count), 1);
  const step = points.length > 1 ? width / (points.length - 1) : 0;
  const coords = points.map((p, i) => `${i * step},${height - (p.count / max) * (height - 4) - 2}`);
  return (
    <svg
      width={width}
      height={height}
      role="img"
      aria-label="Requests over time"
      className="overflow-visible"
    >
      <polyline
        points={coords.join(" ")}
        fill="none"
        stroke="var(--color-accent)"
        strokeWidth="2"
      />
      {coords.map((c) => {
        const [x, y] = c.split(",").map(Number);
        return <circle key={c} cx={x} cy={y} r="2.5" fill="var(--color-accent)" />;
      })}
    </svg>
  );
}
