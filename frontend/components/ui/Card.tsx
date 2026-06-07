import type { HTMLAttributes } from "react";

/** Surface panel with ruled border. Compose freely for list items and instruments. */
export function Card({ className = "", ...props }: HTMLAttributes<HTMLDivElement>) {
  return <div className={`panel p-5 ${className}`} {...props} />;
}
