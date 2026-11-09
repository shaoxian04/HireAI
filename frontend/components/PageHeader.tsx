import type { ReactNode } from "react";
import Link from "next/link";

interface PageHeaderProps {
  title: string;
  description?: ReactNode;
  /** Right-aligned actions (buttons/links). */
  actions?: ReactNode;
  /** Optional back link rendered above the title. */
  back?: { href: string; label: string };
}

/** Page title block shared by every console page: optional back link, title, one-line description, actions. */
export function PageHeader({ title, description, actions, back }: PageHeaderProps) {
  return (
    <header className="page-masthead relative isolate space-y-3 pb-8">
      {back && (
        <Link href={back.href} className="inline-block text-sm font-medium text-muted transition hover:text-accent">
          ← {back.label}
        </Link>
      )}
      <div className="flex flex-wrap items-end justify-between gap-x-6 gap-y-4">
        <div className="min-w-0 space-y-1.5">
          <h1 className="display text-[2.6rem] text-fg">{title}</h1>
          {description && <p className="max-w-2xl text-[0.9375rem] leading-relaxed text-muted">{description}</p>}
        </div>
        {actions && <div className="flex shrink-0 items-center gap-2">{actions}</div>}
      </div>
    </header>
  );
}
