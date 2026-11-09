"use client";

import { useCallback, useEffect, useState, type FormEvent } from "react";
import { api, ApiError } from "@/lib/api";
import { RoleGuard } from "@/components/RoleGuard";
import { AppShell } from "@/components/AppShell";
import type { ApiKeyDTO, CreateApiKeyRequest, CreatedApiKeyDTO } from "@/lib/types";
import { Badge, Button, Field, Input, Modal, statusLabel, SkeletonRows, ConfirmDialog } from "@/components/ui";
import { PageHeader } from "@/components/PageHeader";

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
  const [revoking, setRevoking] = useState<ApiKeyDTO | null>(null);
  const [revokeBusy, setRevokeBusy] = useState(false);

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
    setRevokeBusy(true);
    try {
      await api<ApiKeyDTO>(`/keys/${id}/revoke`, { method: "POST" });
      setRevoking(null);
      load();
    } catch (e) {
      setRevoking(null);
      setError(e instanceof ApiError ? e.message : "Revoke failed");
    } finally {
      setRevokeBusy(false);
    }
  }

  async function copyRaw() {
    if (!revealed) return;
    await navigator.clipboard.writeText(revealed.rawKey);
    setCopied(true);
  }

  return (
    <div className="space-y-10">
      <PageHeader
        title="API keys"
        description="Submit and track tasks from your own code. A key is shown only once, so store it somewhere safe."
        actions={<Button onClick={() => setCreateOpen(true)}>Create key</Button>}
      />

      {error && (
        <p role="alert" className="rounded-md border border-red/30 bg-red/10 px-3 py-2 text-xs text-red">
          {error}
        </p>
      )}

      {keys === null ? (
        <SkeletonRows label="Loading keys…" rows={3} />
      ) : keys.length === 0 ? (
        <div className="rounded-xl border border-dashed border-line-bright bg-surface p-10 text-center">
          <p className="font-semibold">No API keys yet</p>
          <p className="mt-1 text-sm text-muted">Create one to submit tasks from your own code or the MCP server.</p>
        </div>
      ) : (
        <ul className="overflow-hidden rounded-xl border border-line bg-surface">
          {keys.map((k, i) => (
            <li
              key={k.id}
              className={`flex flex-wrap items-center justify-between gap-4 bg-surface px-5 py-4 ${i > 0 ? "border-t border-line" : ""}`}
            >
              <div className="min-w-0">
                <div className="flex items-center gap-3">
                  <span className="font-semibold text-fg">{k.name}</span>
                  <Badge status={k.status === "ACTIVE" ? "RESOLVED" : "CANCELLED"}>{statusLabel(k.status)}</Badge>
                </div>
                <dl className="mt-2 flex flex-wrap gap-x-6 gap-y-1 text-xs">
                  <div className="flex gap-1.5">
                    <dt className="text-dim">Key</dt>
                    <dd className="font-mono text-fg">
                      <span>{k.displayPrefix}</span>…
                    </dd>
                  </div>
                  <div className="flex gap-1.5">
                    <dt className="text-dim">In-flight cap</dt>
                    <dd className="font-semibold text-fg">{k.spendCap != null ? `${k.spendCap} cr` : "None"}</dd>
                  </div>
                  <div className="flex gap-1.5">
                    <dt className="text-dim">Daily cap</dt>
                    <dd className="font-semibold text-fg">{k.dailySpendCap != null ? `${k.dailySpendCap} cr` : "None"}</dd>
                  </div>
                </dl>
              </div>
              {k.status === "ACTIVE" && (
                <Button variant="danger" size="sm" onClick={() => setRevoking(k)}>
                  Revoke
                </Button>
              )}
            </li>
          ))}
        </ul>
      )}

      {/* create modal */}
      <ConfirmDialog
        open={revoking !== null}
        title={`Revoke "${revoking?.name ?? ""}"?`}
        confirmLabel="Revoke key"
        danger
        busy={revokeBusy}
        onConfirm={() => revoking && revoke(revoking.id)}
        onCancel={() => setRevoking(null)}
      >
        Any script or integration using this key stops working immediately. This can&apos;t be undone;
        you would create a new key instead.
      </ConfirmDialog>

      <Modal open={createOpen} onClose={() => setCreateOpen(false)} ariaLabel="Create API key">
        <form onSubmit={create} className="space-y-5 p-6">
          <h2 className="text-xl font-bold tracking-tight">Create API key</h2>
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
          <h2 className="text-xl font-bold tracking-tight">Key created</h2>
          <p className="rounded-md border border-amber/30 bg-amber/10 px-3 py-2 text-xs text-amber">
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
