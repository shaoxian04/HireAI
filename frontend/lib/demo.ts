/**
 * The one-click demo accounts on the sign-in page. Shown in development and tests; in a production
 * build only when NEXT_PUBLIC_DEMO_LOGINS=true. next.config.ts always defines that variable, so the
 * condition is a build-time constant and a normal production build drops these strings from the
 * bundle entirely: a real deployment never ships working credentials, the admin account least of all.
 */
export const SHOW_DEMO_LOGINS =
  process.env.NODE_ENV !== "production" || process.env.NEXT_PUBLIC_DEMO_LOGINS === "true";

export const DEMO_ACCOUNTS: { role: "CLIENT" | "BUILDER" | "ADMIN"; email: string }[] = SHOW_DEMO_LOGINS
  ? [
      { role: "CLIENT", email: "client@hireai.local" },
      { role: "BUILDER", email: "builder@hireai.local" },
      { role: "ADMIN", email: "admin@hireai.local" },
    ]
  : [];

export const DEMO_PASSWORD = SHOW_DEMO_LOGINS ? "DemoPass123!" : "";
