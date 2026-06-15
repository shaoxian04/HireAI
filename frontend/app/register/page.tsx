"use client";

import { useState, type FormEvent } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useAuth } from "@/lib/auth";
import { ApiError } from "@/lib/api";
import { Button, Field, Input } from "@/components/ui";

const API_ORIGIN = process.env.NEXT_PUBLIC_API_ORIGIN ?? "http://localhost:8080";

export default function RegisterPage() {
  const { register } = useAuth();
  const router = useRouter();
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [displayName, setDisplayName] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function onSubmit(e: FormEvent) {
    e.preventDefault();
    setError(null);
    setBusy(true);
    try {
      await register(email, password, displayName || undefined);
      router.replace("/client");
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Registration failed");
    } finally {
      setBusy(false);
    }
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
              Create account
            </p>
            <h1 className="mt-3 text-3xl font-extrabold tracking-tight">Join the marketplace</h1>
            <p className="mt-2 text-sm text-muted">Start as a client — you can become a builder anytime.</p>
          </div>

          <div className="panel hud p-6">
            <form onSubmit={onSubmit} className="space-y-4">
              <Field label="Name" htmlFor="displayName">
                <Input id="displayName" value={displayName} onChange={(e) => setDisplayName(e.target.value)} />
              </Field>
              <Field label="Email" htmlFor="email">
                <Input id="email" type="email" autoComplete="username" value={email}
                  onChange={(e) => setEmail(e.target.value)} required />
              </Field>
              <Field label="Password" htmlFor="password">
                <Input id="password" type="password" autoComplete="new-password" value={password}
                  onChange={(e) => setPassword(e.target.value)} required minLength={8} />
              </Field>
              {error && (
                <p role="alert" className="rounded-md border border-red/30 bg-red/10 px-3 py-2 font-mono text-xs text-red">
                  {error}
                </p>
              )}
              <Button type="submit" disabled={busy} className="w-full">
                {busy ? "Creating…" : "Create account ▸"}
              </Button>
            </form>

            <div className="my-4 flex items-center gap-3">
              <span className="h-px flex-1 bg-line" />
              <span className="font-mono text-[0.6rem] uppercase tracking-[0.18em] text-dim">or</span>
              <span className="h-px flex-1 bg-line" />
            </div>
            <a href={`${API_ORIGIN}/oauth2/authorization/google`}
              className="flex w-full items-center justify-center gap-2 rounded-md border border-line bg-surface px-3 py-2.5 font-mono text-xs uppercase tracking-wider text-fg transition hover:border-accent/50">
              Continue with Google
            </a>
          </div>

          <p className="mt-5 text-center font-mono text-xs text-muted">
            Already have an account?{" "}
            <Link href="/login" className="text-accent hover:underline">Sign in</Link>
          </p>
        </div>
      </div>
    </div>
  );
}
