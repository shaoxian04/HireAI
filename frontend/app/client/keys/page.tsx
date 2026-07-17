"use client";

import { useCallback, useEffect, useState, type FormEvent } from "react";
import { api, ApiError } from "@/lib/api";
import { RoleGuard } from "@/components/RoleGuard";
import { AppShell } from "@/components/AppShell";
import type { ApiKeyDTO, CreateApiKeyRequest, CreatedApiKeyDTO } from "@/lib/types";
import { Badge, Button, Field, Input, Modal } from "@/components/ui";

function ClientKeys() {
  const [keys, setKeys] = useState<ApiKeyDTO[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [createOpen, setCreateOpen] = useState(false);
  const [name, setName] = useState("");
  const [spendCap, setSpendCap] = useState("");
  const [dailyCap, setDailyCap] = useState("");
  const [creating, setCreating] = useState(false);
  const [revealed, setRevealed] = useState<CreatedApiKeyDTO | null>(null);
  const [copied, setCopied] = useState(false);

  // useCallback (rather than a plain function + useEffect(load, [])) so the effect below can
  // list `load` as a dependency and satisfy react-hooks/exhaustive-deps cleanly — no disable needed.
  const load = useCallback(() => {
    api<ApiKeyDTO[]>("/keys")
      .then(setKeys)
      .catch((e) => setError(e instanceof ApiError ? e.message : "Failed to load keys"));
  }, []);
  useEffect(() => {
    load();
  }, [load]);

  async function create(e: FormEvent) {
    e.preventDefault();
    setError(null);
    setCreating(true);
    try {
      const body: CreateApiKeyRequest = {
        name,
        spendCap: spendCap ? Number(spendCap) : null,
        dailySpendCap: dailyCap ? Number(dailyCap) : null,
      };
      const created = await api<CreatedApiKeyDTO>("/keys", {
        method: "POST",
        body: JSON.stringify(body),
      });
      setCreateOpen(false);
      setName("");
      setSpendCap("");
      setDailyCap("");
      setRevealed(created);
      load();
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "Create failed");
    } finally {
      setCreating(false);
    }
  }

  async function revoke(id: string) {
    setError(null);
    try {
      await api<ApiKeyDTO>(`/keys/${id}/revoke`, { method: "POST" });
      load();
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "Revoke failed");
    }
  }

  async function copyRaw() {
    if (!revealed) return;
    await navigator.clipboard.writeText(revealed.rawKey);
    setCopied(true);
  }

  return (
    <div className="space-y-10">
      <header className="flex items-end justify-between">
        <div>
          <p className="eyebrow flex items-center gap-2">
            <span className="inline-block h-px w-6 bg-accent" />
            Client console
          </p>
          <h1 className="mt-3 text-3xl font-extrabold tracking-tight">API keys</h1>
          <p className="mt-2 text-sm text-muted">
            Submit and track tasks programmatically. Keys are shown once — store them securely.
          </p>
        </div>
        <Button onClick={() => setCreateOpen(true)}>+ Create key</Button>
      </header>

      {error && (
        <p role="alert" className="rounded-md border border-red/30 bg-red/10 px-3 py-2 font-mono text-xs text-red">
          {error}
        </p>
      )}

      {keys === null ? (
        <p className="font-mono text-sm text-dim">Loading keys…</p>
      ) : keys.length === 0 ? (
        <div className="panel p-10 text-center">
          <p className="font-mono text-sm text-muted">No API keys yet.</p>
          <p className="mt-1 font-mono text-xs text-dim">Create one to submit tasks over the API.</p>
        </div>
      ) : (
        <ul className="overflow-hidden rounded-xl border border-line">
          {keys.map((k, i) => (
            <li
              key={k.id}
              className={`flex flex-wrap items-center justify-between gap-4 bg-surface px-5 py-4 ${
                i > 0 ? "border-t border-line" : ""
              }`}
            >
              <div className="min-w-0">
                <div className="flex items-center gap-3">
                  <span className="font-medium text-fg">{k.name}</span>
                  <Badge status={k.status === "ACTIVE" ? "RESOLVED" : "CANCELLED"}>{k.status}</Badge>
                </div>
                <p className="mt-1 font-mono text-xs text-dim">
                  <span className="text-muted">{k.displayPrefix}</span>… ·{" "}
                  {k.spendCap != null ? `${k.spendCap} cr concurrent` : "uncapped"} ·{" "}
                  {k.dailySpendCap != null ? `${k.dailySpendCap} cr/day` : "no daily cap"}
                </p>
              </div>
              {k.status === "ACTIVE" && (
                <Button variant="ghost" onClick={() => revoke(k.id)}>
                  Revoke
                </Button>
              )}
            </li>
          ))}
        </ul>
      )}

      {/* create modal */}
      <Modal open={createOpen} onClose={() => setCreateOpen(false)} ariaLabel="Create API key">
        <form onSubmit={create} className="space-y-5 p-6">
          <h2 className="text-xl font-extrabold tracking-tight">Create API key</h2>
          <Field label="Name" htmlFor="key-name">
            <Input id="key-name" value={name} onChange={(e) => setName(e.target.value)} required />
          </Field>
          <Field label="Concurrent spend cap (credits, optional)" htmlFor="key-cap">
            <Input id="key-cap" type="number" min={0} value={spendCap}
                   onChange={(e) => setSpendCap(e.target.value)} placeholder="uncapped" />
          </Field>
          <Field label="Daily spend cap (credits / 24h, optional)" htmlFor="key-daily">
            <Input id="key-daily" type="number" min={0} value={dailyCap}
                   onChange={(e) => setDailyCap(e.target.value)} placeholder="no daily cap" />
          </Field>
          <div className="flex justify-end gap-2">
            <Button type="button" variant="ghost" onClick={() => setCreateOpen(false)}>
              Cancel
            </Button>
            <Button type="submit" disabled={creating || !name}>
              {creating ? "…" : "Create"}
            </Button>
          </div>
        </form>
      </Modal>

      {/* reveal-once modal */}
      <Modal open={revealed !== null} onClose={() => { setRevealed(null); setCopied(false); }}
             ariaLabel="Your new API key">
        <div className="space-y-5 p-6">
          <h2 className="text-xl font-extrabold tracking-tight">Key created</h2>
          <p className="rounded-md border border-amber/30 bg-amber/10 px-3 py-2 font-mono text-xs text-amber">
            Copy it now — you won&apos;t see it again.
          </p>
          <div className="flex items-center gap-2">
            <code className="flex-1 truncate rounded-md border border-line bg-surface-2 px-3 py-2 font-mono text-sm text-accent">
              {revealed?.rawKey}
            </code>
            <Button type="button" onClick={copyRaw}>{copied ? "Copied ✓" : "Copy"}</Button>
          </div>
          <div className="flex justify-end">
            <Button type="button" variant="ghost" onClick={() => { setRevealed(null); setCopied(false); }}>
              Done
            </Button>
          </div>
        </div>
      </Modal>
    </div>
  );
}

export default function Page() {
  return (
    <AppShell>
      <RoleGuard role="CLIENT">
        <ClientKeys />
      </RoleGuard>
    </AppShell>
  );
}
