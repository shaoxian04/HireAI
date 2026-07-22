"use client";

import { useCallback, useEffect, useState, type FormEvent } from "react";
import { api, ApiError } from "@/lib/api";
import { RoleGuard } from "@/components/RoleGuard";
import { AppShell } from "@/components/AppShell";
import { Badge, Button, Card, Field, Input, Select } from "@/components/ui";
import type { ApiKeyDTO, WebhookDeliveryDTO, WebhookSubscriptionDTO } from "@/lib/types";

type StatusFilter = "" | "PENDING" | "DELIVERED" | "DEAD";

function ClientWebhooks() {
  const [keys, setKeys] = useState<ApiKeyDTO[] | null>(null);
  const [selectedKeyId, setSelectedKeyId] = useState("");
  const [subscription, setSubscription] = useState<WebhookSubscriptionDTO | null>(null);
  const [callbackUrl, setCallbackUrl] = useState("");
  const [saving, setSaving] = useState(false);
  const [rotating, setRotating] = useState(false);
  const [deactivating, setDeactivating] = useState(false);
  const [copied, setCopied] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [deliveries, setDeliveries] = useState<WebhookDeliveryDTO[] | null>(null);
  const [statusFilter, setStatusFilter] = useState<StatusFilter>("");
  const [resendingId, setResendingId] = useState<string | null>(null);
  const [hasDeadDeliveries, setHasDeadDeliveries] = useState(false);

  // useCallback (rather than a plain function + useEffect(load, [])) so the effects below can list
  // these as dependencies and satisfy react-hooks/exhaustive-deps cleanly — no disable needed.
  const loadKeys = useCallback(() => {
    api<ApiKeyDTO[]>("/keys")
      .then((ks) => {
        setKeys(ks);
        setSelectedKeyId((prev) => (prev && ks.some((k) => k.id === prev) ? prev : (ks[0]?.id ?? "")));
      })
      .catch((e) => setError(e instanceof ApiError ? e.message : "Failed to load API keys"));
  }, []);
  useEffect(() => {
    loadKeys();
  }, [loadKeys]);

  const loadSubscription = useCallback((apiKeyId: string) => {
    api<WebhookSubscriptionDTO>(`/webhooks/subscription?apiKeyId=${apiKeyId}`)
      .then((s) => {
        setSubscription(s);
        setCallbackUrl(s.callbackUrl);
      })
      .catch((e) => {
        // 404 just means this key has no subscription registered yet — not a page-level error.
        setSubscription(null);
        setCallbackUrl("");
        if (!(e instanceof ApiError && e.status === 404)) {
          setError(e instanceof ApiError ? e.message : "Failed to load webhook subscription");
        }
      });
  }, []);
  useEffect(() => {
    if (selectedKeyId) loadSubscription(selectedKeyId);
  }, [selectedKeyId, loadSubscription]);

  const loadDeliveries = useCallback(() => {
    const q = statusFilter ? `?status=${statusFilter}` : "";
    api<WebhookDeliveryDTO[]>(`/webhooks/deliveries${q}`)
      .then(setDeliveries)
      .catch((e) => setError(e instanceof ApiError ? e.message : "Failed to load delivery log"));
  }, [statusFilter]);
  useEffect(() => {
    loadDeliveries();
  }, [loadDeliveries]);

  // Account-wide DEAD health signal — deliberately NOT derived from `deliveries` (which is
  // scoped by `statusFilter`): a filter like "Pending" must never hide the fact that DEAD
  // deliveries exist elsewhere in the account, so this fetches status=DEAD unconditionally.
  const loadDeadStatus = useCallback(() => {
    api<WebhookDeliveryDTO[]>("/webhooks/deliveries?status=DEAD")
      .then((rows) => setHasDeadDeliveries(rows.length > 0))
      .catch(() => {
        // best-effort health signal only — a failure here shouldn't surface a page-level error
      });
  }, []);
  useEffect(() => {
    loadDeadStatus();
  }, [loadDeadStatus]);

  // Reset the "Copied ✓" affordance when switching API keys so it doesn't linger and imply the
  // newly-selected key's secret was just copied.
  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect -- intentional reset-on-key-switch so a newly selected key never shows a stale "Copied" affordance
    setCopied(false);
  }, [selectedKeyId]);

  async function registerCallback(e: FormEvent) {
    e.preventDefault();
    if (!selectedKeyId) return;
    if (!callbackUrl.startsWith("https://")) {
      setError("Callback URL must be HTTPS.");
      return;
    }
    setError(null);
    setSaving(true);
    try {
      const s = await api<WebhookSubscriptionDTO>("/webhooks/subscription", {
        method: "POST",
        body: JSON.stringify({ apiKeyId: selectedKeyId, callbackUrl }),
      });
      setSubscription(s);
      setCallbackUrl(s.callbackUrl);
      setCopied(false);
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "Registration failed");
    } finally {
      setSaving(false);
    }
  }

  async function rotateSecret() {
    if (!selectedKeyId) return;
    setError(null);
    setRotating(true);
    try {
      const s = await api<WebhookSubscriptionDTO>(
        `/webhooks/subscription/rotate-secret?apiKeyId=${selectedKeyId}`,
        { method: "POST" },
      );
      setSubscription(s);
      setCopied(false);
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "Rotate secret failed");
    } finally {
      setRotating(false);
    }
  }

  async function deactivate() {
    if (!selectedKeyId) return;
    setError(null);
    setDeactivating(true);
    try {
      await api<void>(`/webhooks/subscription/deactivate?apiKeyId=${selectedKeyId}`, { method: "POST" });
      loadSubscription(selectedKeyId);
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "Deactivate failed");
    } finally {
      setDeactivating(false);
    }
  }

  async function resend(eventId: string) {
    setError(null);
    setResendingId(eventId);
    try {
      await api<void>(`/webhooks/deliveries/${eventId}/redeliver`, { method: "POST" });
      loadDeliveries();
      loadDeadStatus();
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "Resend failed");
    } finally {
      setResendingId(null);
    }
  }

  async function copySecret() {
    if (!subscription) return;
    await navigator.clipboard.writeText(subscription.signingSecret);
    setCopied(true);
  }

  return (
    <div className="space-y-10">
      <header>
        <p className="eyebrow flex items-center gap-2">
          <span className="inline-block h-px w-6 bg-accent" />
          Client console
        </p>
        <h1 className="mt-3 text-3xl font-extrabold tracking-tight">Webhooks</h1>
        <p className="mt-2 text-sm text-muted">
          Push task-lifecycle events to your own endpoint instead of polling — register a callback URL
          per API key, then verify each delivery with its signing secret.
        </p>
      </header>

      {error && (
        <p role="alert" className="rounded-md border border-red/30 bg-red/10 px-3 py-2 font-mono text-xs text-red">
          {error}
        </p>
      )}

      {/* subscription management */}
      <Card className="space-y-5">
        <h2 className="text-lg font-bold tracking-tight">Subscription</h2>

        {keys === null ? (
          <p className="font-mono text-sm text-dim">Loading API keys…</p>
        ) : keys.length === 0 ? (
          <p className="font-mono text-sm text-muted">
            No API keys yet. Create one on the{" "}
            <a href="/client/keys" className="text-accent hover:underline">
              API keys
            </a>{" "}
            page before registering a webhook.
          </p>
        ) : (
          <>
            <Field label="API key" htmlFor="webhook-key-select">
              <Select
                id="webhook-key-select"
                value={selectedKeyId}
                onChange={(e) => setSelectedKeyId(e.target.value)}
              >
                {keys.map((k) => (
                  <option key={k.id} value={k.id}>
                    {k.name} ({k.displayPrefix})
                  </option>
                ))}
              </Select>
            </Field>

            <form onSubmit={registerCallback} className="space-y-4">
              <Field label="Callback URL" htmlFor="callback-url" hint="Must be HTTPS — this is where events are POSTed.">
                <Input
                  id="callback-url"
                  type="url"
                  placeholder="https://your-service.example.com/hireai/webhook"
                  value={callbackUrl}
                  onChange={(e) => setCallbackUrl(e.target.value)}
                  required
                />
              </Field>
              <div className="flex flex-wrap gap-2">
                <Button type="submit" disabled={saving || !selectedKeyId}>
                  {saving ? "…" : subscription ? "Save" : "Register"}
                </Button>
                {subscription && (
                  <>
                    <Button type="button" variant="ghost" onClick={rotateSecret} disabled={rotating}>
                      {rotating ? "…" : "Rotate secret"}
                    </Button>
                    {subscription.active && (
                      <Button type="button" variant="ghost" onClick={deactivate} disabled={deactivating}>
                        {deactivating ? "…" : "Deactivate"}
                      </Button>
                    )}
                  </>
                )}
              </div>
            </form>

            {subscription && (
              <div className="space-y-3 rounded-md border border-line bg-surface-2 p-4">
                <div className="flex items-center justify-between">
                  <span className="font-mono text-[0.7rem] uppercase tracking-[0.18em] text-muted">
                    Signing secret
                  </span>
                  <Badge status={subscription.active ? "RESOLVED" : "CANCELLED"}>
                    {subscription.active ? "Active" : "Inactive"}
                  </Badge>
                </div>
                <div className="flex items-center gap-2">
                  <code className="flex-1 truncate rounded-md border border-line bg-surface px-3 py-2 font-mono text-sm text-accent">
                    {subscription.signingSecret}
                  </code>
                  <Button type="button" onClick={copySecret}>
                    {copied ? "Copied ✓" : "Copy"}
                  </Button>
                </div>
                <p className="font-mono text-xs text-dim">
                  Keep this private. Each delivery carries a <code>t=…,v1=…</code> header — verify it as
                  HMAC-SHA256 of <code>{"{timestamp}.{body}"}</code> using this secret before trusting the payload.
                </p>
              </div>
            )}
          </>
        )}
      </Card>

      {/* delivery log */}
      <div className="space-y-4">
        <div className="flex flex-wrap items-end justify-between gap-3">
          <h2 className="text-lg font-bold tracking-tight">Delivery log</h2>
          <Field label="Status" htmlFor="delivery-status-filter">
            <Select
              id="delivery-status-filter"
              value={statusFilter}
              onChange={(e) => setStatusFilter(e.target.value as StatusFilter)}
            >
              <option value="">All</option>
              <option value="PENDING">Pending</option>
              <option value="DELIVERED">Delivered</option>
              <option value="DEAD">Dead</option>
            </Select>
          </Field>
        </div>

        {hasDeadDeliveries && (
          <p className="rounded-md border border-red/30 bg-red/10 px-3 py-2 font-mono text-xs text-red">
            One or more deliveries exhausted retries and are marked DEAD — resend once your endpoint is
            reachable again.
          </p>
        )}

        <Card className="overflow-x-auto p-0">
          <table className="w-full text-sm">
            <thead>
              <tr className="border-b border-line text-left font-mono text-[0.65rem] uppercase tracking-wider text-dim">
                <th className="p-3">Status</th>
                <th className="p-3">Event</th>
                <th className="p-3">Task</th>
                <th className="p-3">Attempts</th>
                <th className="p-3">Last error</th>
                <th className="p-3">Updated</th>
                <th className="p-3" />
              </tr>
            </thead>
            <tbody>
              {(deliveries ?? []).map((d) => (
                <tr key={d.eventId} className="border-b border-line/50 hover:bg-surface-2/50">
                  <td className="p-3">
                    <Badge status={d.status}>{d.status}</Badge>
                  </td>
                  <td className="p-3 font-mono text-xs text-fg">{d.eventType}</td>
                  <td className="p-3 font-mono text-xs text-dim">{d.taskId}</td>
                  <td className="p-3 font-mono text-xs text-muted">{d.attempts}</td>
                  <td className="p-3 font-mono text-xs text-red">{d.lastError ?? "—"}</td>
                  <td className="p-3 font-mono text-xs text-dim">
                    {new Date(d.deliveredAt ?? d.nextAttemptAt ?? d.createdAt).toLocaleString()}
                  </td>
                  <td className="p-3 text-right">
                    <Button
                      variant="ghost"
                      onClick={() => resend(d.eventId)}
                      disabled={resendingId === d.eventId}
                    >
                      {resendingId === d.eventId ? "…" : "Resend"}
                    </Button>
                  </td>
                </tr>
              ))}
              {deliveries !== null && deliveries.length === 0 && (
                <tr>
                  <td colSpan={7} className="p-3 font-mono text-xs text-dim">
                    No deliveries yet.
                  </td>
                </tr>
              )}
              {deliveries === null && (
                <tr>
                  <td colSpan={7} className="p-3 font-mono text-xs text-dim">
                    Loading delivery log…
                  </td>
                </tr>
              )}
            </tbody>
          </table>
        </Card>
      </div>
    </div>
  );
}

export default function Page() {
  return (
    <AppShell>
      <RoleGuard role="CLIENT">
        <ClientWebhooks />
      </RoleGuard>
    </AppShell>
  );
}
