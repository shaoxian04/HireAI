/** A blank ticket stub: the empty-state mark used wherever a list has nothing filed in it yet. */
export function EmptyTicket({ className = "" }: { className?: string }) {
  return (
    <svg viewBox="0 0 40 28" className={`mx-auto mb-3 h-7 w-10 text-line-bright ${className}`} aria-hidden>
      <path
        d="M2 4a2 2 0 0 1 2-2h32a2 2 0 0 1 2 2v6a4 4 0 0 0 0 8v6a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2v-6a4 4 0 0 0 0-8z"
        fill="none"
        stroke="currentColor"
        strokeWidth="1.6"
        strokeDasharray="3 2.5"
      />
    </svg>
  );
}
