/** Shimmering placeholder block. Size it with utilities (e.g. `h-4 w-40`). Hidden from AT. */
export function Skeleton({ className = "" }: { className?: string }) {
  return <span aria-hidden className={`skeleton block ${className}`} />;
}

/**
 * Placeholder rows for a list or table that is still loading. Carries an sr-only label so
 * screen readers (and tests) still hear what is loading.
 */
export function SkeletonRows({ label, rows = 4 }: { label: string; rows?: number }) {
  return (
    <div role="status" className="overflow-hidden rounded-lg border border-line">
      <span className="sr-only">{label}</span>
      {Array.from({ length: rows }, (_, i) => (
        <div key={i} className="flex items-center gap-4 border-b border-line px-4 py-3.5 last:border-b-0">
          <Skeleton className="h-4 w-2/5" />
          <Skeleton className="ml-auto h-4 w-16" />
          <Skeleton className="h-4 w-20" />
        </div>
      ))}
    </div>
  );
}
