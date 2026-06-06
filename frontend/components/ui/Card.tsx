import type { HTMLAttributes } from "react";

/** Surface container with border + subtle shadow. Compose freely for list items and panels. */
export function Card({ className = "", ...props }: HTMLAttributes<HTMLDivElement>) {
  return (
    <div
      className={`rounded-lg border border-slate-200 bg-white p-5 shadow-sm ${className}`}
      {...props}
    />
  );
}
