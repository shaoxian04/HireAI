"use client";

import { useState, type FormEvent } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useAuth, homeFor } from "@/lib/auth";
import { ApiError } from "@/lib/api";
import { Button, Field, Input } from "@/components/ui";
import { GoogleSignInButton } from "@/components/GoogleSignInButton";

const DEMO = [
  { role: "CLIENT", email: "client@hireai.local" },
  { role: "BUILDER", email: "builder@hireai.local" },
];
const DEMO_PASSWORD = "DemoPass123!";

export default function LoginPage() {
  const { login } = useAuth();
  const router = useRouter();
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function onSubmit(e: FormEvent) {
    e.preventDefault();
    setError(null);
    setBusy(true);
    try {
      const res = await login(email, password);
      router.replace(homeFor(res.roles));
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Login failed");
    } finally {
      setBusy(false);
    }
  }

  function fill(addr: string) {
    setEmail(addr);
    setPassword(DEMO_PASSWORD);
  }

  return (
    <div className="flex min-h-screen flex-col">
      <div className="mx-auto flex w-full max-w-6xl items-center px-5 py-6 sm:px-6">
        <Link href="/" className="flex items-center gap-2.5">
          <span className="grid size-7 place-items-center rounded-[5px] border border-accent/50 bg-accent/10 glow">
            <span className="size-2.5 rounded-[2px] bg-accent" />
          </span>
          <span className="font-mono text-sm font-bold tracking-[0.22em]">
            HIRE<span className="text-accent">AI</span>
          </span>
        </Link>
      </div>

      <div className="flex flex-1 items-center justify-center px-5 pb-20">
        <div className="w-full max-w-sm">
          <div className="mb-6">
            <p className="eyebrow flex items-center gap-2">
              <span className="inline-block h-px w-6 bg-accent" />
              Authenticate
            </p>
            <h1 className="mt-3 text-3xl font-extrabold tracking-tight">Access the console</h1>
            <p className="mt-2 text-sm text-muted">Sign in to route or build.</p>
          </div>

          <div className="panel hud p-6">
            <form onSubmit={onSubmit} className="space-y-4">
              <Field label="Email" htmlFor="email">
                <Input
                  id="email"
                  type="email"
                  autoComplete="username"
                  value={email}
                  onChange={(e) => setEmail(e.target.value)}
                  required
                />
              </Field>
              <Field label="Password" htmlFor="password">
                <Input
                  id="password"
                  type="password"
                  autoComplete="current-password"
                  value={password}
                  onChange={(e) => setPassword(e.target.value)}
                  required
                />
              </Field>
              {error && (
                <p
                  role="alert"
                  className="rounded-md border border-red/30 bg-red/10 px-3 py-2 font-mono text-xs text-red"
                >
                  {error}
                </p>
              )}
              <Button type="submit" disabled={busy} className="w-full">
                {busy ? "Authenticating…" : "Sign in ▸"}
              </Button>
            </form>

            <div className="my-4 flex items-center gap-3">
              <span className="h-px flex-1 bg-line" />
              <span className="font-mono text-[0.6rem] uppercase tracking-[0.18em] text-dim">or</span>
              <span className="h-px flex-1 bg-line" />
            </div>
            <GoogleSignInButton />
          </div>

          <div className="mt-5 rounded-md border border-line bg-surface-2/60 p-4">
            <p className="font-mono text-[0.6rem] uppercase tracking-[0.18em] text-dim">
              Demo access — click to fill
            </p>
            <div className="mt-3 space-y-2">
              {DEMO.map((d) => (
                <button
                  key={d.email}
                  type="button"
                  onClick={() => fill(d.email)}
                  className="flex w-full items-center justify-between rounded border border-line bg-surface px-3 py-2 text-left transition hover:border-accent/50"
                >
                  <span className="font-mono text-xs text-fg">{d.email}</span>
                  <span className="font-mono text-[0.6rem] uppercase tracking-wider text-accent">
                    {d.role}
                  </span>
                </button>
              ))}
            </div>
            <p className="mt-3 font-mono text-[0.65rem] text-dim">
              password: <span className="text-muted">{DEMO_PASSWORD}</span>
            </p>
          </div>
          <p className="mt-5 text-center font-mono text-xs text-muted">
            No account?{" "}
            <Link href="/register" className="text-accent hover:underline">
              Create one
            </Link>
          </p>
        </div>
      </div>
    </div>
  );
}
