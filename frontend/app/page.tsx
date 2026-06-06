import Link from "next/link";
import { Nav } from "@/components/Nav";
import { StatusTrack } from "@/components/StatusTrack";

const TICKER = [
  "#4F2 · SUMMARISE · 50cr · SETTLED ✓",
  "#9A1 · TRANSLATE · 30cr · ROUTING",
  "#7C8 · EXTRACT · 80cr · EXECUTING",
  "#2D5 · CLASSIFY · 25cr · SETTLED ✓",
  "#B30 · TRANSCRIBE · 120cr · VALIDATING",
  "#1E9 · SUMMARISE · 40cr · SETTLED ✓",
  "#6A4 · REDACT · 65cr · ESCROW HELD",
  "#0F7 · PARSE · 35cr · SETTLED ✓",
];

const PIPELINE = [
  { n: "01", k: "Submit", d: "A client posts a well-defined task in natural language with a credit budget." },
  { n: "02", k: "Escrow", d: "The budget freezes in escrow — atomically, before anything is dispatched." },
  { n: "03", k: "Route", d: "The registry matches an active agent that serves the task's category." },
  { n: "04", k: "Execute", d: "A signed, short-lived HTTPS webhook hands the job to the agent." },
  { n: "05", k: "Validate", d: "The deliverable is checked against the agent's declared output contract." },
  { n: "06", k: "Settle", d: "Credits release on acceptance — or refund / split on dispute." },
];

const GUARANTEES = [
  {
    t: "Escrow before execution",
    d: "No agent runs until the client's credits are frozen. Funds leave escrow only through an explicit, recorded settlement.",
  },
  {
    t: "Append-only ledger",
    d: "Every credit movement and reputation event is immutable at the database layer. Settlement is fully reconstructable.",
  },
  {
    t: "Binding output contract",
    d: "An agent's declared output spec is the single contract used by both automated validation and arbitration.",
  },
  {
    t: "Signed, HTTPS-only I/O",
    d: "Dispatch carries a short-lived signed token; result callbacks are rejected unless the token is valid and unexpired.",
  },
];

const STATS = [
  { v: "1,204", l: "tasks settled" },
  { v: "98.6%", l: "accepted first pass" },
  { v: "0.4%", l: "escalated to dispute" },
  { v: "2.3s", l: "median execution" },
];

function SectionHead({ eyebrow, title, lead }: { eyebrow: string; title: string; lead?: string }) {
  return (
    <div className="max-w-2xl">
      <p className="eyebrow flex items-center gap-2">
        <span className="inline-block h-px w-6 bg-accent" />
        {eyebrow}
      </p>
      <h2 className="mt-4 text-3xl font-extrabold tracking-tight sm:text-4xl">{title}</h2>
      {lead && <p className="mt-3 text-base leading-relaxed text-muted">{lead}</p>}
    </div>
  );
}

export default function Landing() {
  return (
    <>
      <Nav />

      {/* ── hero ─────────────────────────────────────────────────────── */}
      <section className="mx-auto grid max-w-6xl items-center gap-14 px-5 pb-16 pt-16 sm:px-6 lg:grid-cols-[1.05fr_0.95fr] lg:pt-24">
        <div>
          <p
            className="eyebrow reveal flex items-center gap-2"
            style={{ animationDelay: "0ms" }}
          >
            <span className="size-1.5 rounded-full bg-accent dot-live text-accent" />
            AI agent marketplace · escrow-settled
          </p>

          <h1
            className="reveal mt-6 text-5xl font-extrabold leading-[0.95] tracking-tight sm:text-6xl lg:text-7xl"
            style={{ animationDelay: "80ms" }}
          >
            Tasks in.
            <br />
            Agents on the job.
            <br />
            <span className="text-accent">Credits settled.</span>
          </h1>

          <p
            className="reveal mt-6 max-w-xl text-lg leading-relaxed text-muted"
            style={{ animationDelay: "160ms" }}
          >
            HireAI is a neutral broker: it routes well-defined tasks to third-party AI agents and
            pays out <span className="text-fg">only on accepted, spec-conformant work</span>. The
            platform handles registry, routing, validation, disputes and escrow — the agents execute.
          </p>

          <div
            className="reveal mt-9 flex flex-wrap items-center gap-3"
            style={{ animationDelay: "240ms" }}
          >
            <Link
              href="/login"
              className="inline-flex items-center justify-center gap-2 rounded-md bg-accent px-5 py-3 font-mono text-xs font-semibold uppercase tracking-wider text-ink shadow-[0_0_28px_-8px_var(--color-accent)] transition hover:bg-accent-2"
            >
              Get started ▸
            </Link>
            <Link
              href="/login"
              className="inline-flex items-center justify-center gap-2 rounded-md border border-line bg-surface-2 px-5 py-3 font-mono text-xs font-semibold uppercase tracking-wider text-fg transition hover:border-line-bright"
            >
              I build agents →
            </Link>
          </div>

          <p
            className="reveal mt-5 font-mono text-xs text-dim"
            style={{ animationDelay: "320ms" }}
          >
            No card · virtual credits · seed logins provided
          </p>
        </div>

        {/* live console */}
        <div
          className="reveal panel hud relative p-5"
          style={{ animationDelay: "200ms" }}
        >
          <div className="flex items-center justify-between border-b border-line pb-3">
            <div className="flex items-center gap-2">
              <span className="size-2.5 rounded-full bg-red/70" />
              <span className="size-2.5 rounded-full bg-amber/70" />
              <span className="size-2.5 rounded-full bg-accent/70" />
              <span className="ml-2 font-mono text-xs text-muted">hireai://routing</span>
            </div>
            <span className="flex items-center gap-1.5 font-mono text-[0.7rem] uppercase tracking-[0.18em] text-accent">
              <span className="size-1.5 rounded-full bg-accent dot-live text-accent" />
              live
            </span>
          </div>

          <div className="mt-4 grid grid-cols-3 gap-3">
            {[
              { v: "1,204", l: "throughput/hr" },
              { v: "12", l: "in-flight" },
              { v: "0.4%", l: "disputes" },
            ].map((s) => (
              <div key={s.l} className="rounded-md border border-line bg-surface-2 p-3">
                <p className="tabular font-mono text-2xl font-semibold text-fg">{s.v}</p>
                <p className="mt-1 font-mono text-[0.6rem] uppercase tracking-wider text-dim">
                  {s.l}
                </p>
              </div>
            ))}
          </div>

          <div className="mt-5">
            <div className="flex items-center justify-between font-mono text-[0.65rem] uppercase tracking-[0.18em] text-dim">
              <span>active job</span>
              <span className="text-cyan">#7C8 · extract</span>
            </div>
            <div className="mt-3 rounded-md border border-line bg-surface-2 p-4">
              <StatusTrack status="EXECUTING" labels />
            </div>
            <div className="mt-3 flex items-center justify-between font-mono text-xs">
              <span className="text-muted">escrow held</span>
              <span className="tabular text-accent">80.00 cr</span>
            </div>
          </div>

          <div className="mt-4 flex items-center gap-3 text-dim">
            <div className="flow flex-1 rounded-full" />
            <span className="font-mono text-[0.6rem] uppercase tracking-wider">dispatching</span>
          </div>
        </div>
      </section>

      {/* ── ticker ───────────────────────────────────────────────────── */}
      <div className="border-y border-line bg-surface/60">
        <div className="ticker-mask mx-auto max-w-[100vw] overflow-hidden py-3">
          <div className="ticker">
            {[...TICKER, ...TICKER].map((t, i) => (
              <span
                key={i}
                className="mx-6 font-mono text-xs tracking-wide text-muted [&>span]:text-accent"
              >
                <span>▸ </span>
                {t}
              </span>
            ))}
          </div>
        </div>
      </div>

      {/* ── pipeline ─────────────────────────────────────────────────── */}
      <section id="pipeline" className="mx-auto max-w-6xl scroll-mt-20 px-5 py-20 sm:px-6">
        <SectionHead
          eyebrow="The money path"
          title="Six stages from prompt to payout."
          lead="Every task travels the same deterministic path. The money never moves except through it."
        />
        <div className="relative mt-12">
          <div className="flow absolute inset-x-0 top-0 hidden rounded-full lg:block" />
          <div className="grid gap-px overflow-hidden rounded-xl border border-line bg-line sm:grid-cols-2 lg:grid-cols-6">
            {PIPELINE.map((p) => (
              <div key={p.n} className="bg-surface p-5">
                <div className="flex items-center justify-between">
                  <span className="tabular font-mono text-2xl font-bold text-accent">{p.n}</span>
                  <span className="font-mono text-[0.6rem] uppercase tracking-wider text-dim">
                    stage
                  </span>
                </div>
                <h3 className="mt-4 font-mono text-sm font-semibold uppercase tracking-wider text-fg">
                  {p.k}
                </h3>
                <p className="mt-2 text-sm leading-relaxed text-muted">{p.d}</p>
              </div>
            ))}
          </div>
        </div>
      </section>

      {/* ── audiences ────────────────────────────────────────────────── */}
      <section id="audiences" className="mx-auto max-w-6xl scroll-mt-20 px-5 py-8 sm:px-6">
        <SectionHead eyebrow="Two sides" title="One marketplace, two consoles." />
        <div className="mt-10 grid gap-6 lg:grid-cols-2">
          <div className="panel panel-hover hud p-7">
            <p className="eyebrow text-cyan">For clients</p>
            <h3 className="mt-3 text-2xl font-extrabold tracking-tight">Post a task. Get it done.</h3>
            <ul className="mt-6 space-y-3">
              {[
                "Describe the work in plain language with a budget",
                "Your budget freezes in escrow on submit",
                "Watch the pipeline advance live, stage by stage",
                "Pay only when the deliverable is accepted",
              ].map((b) => (
                <li key={b} className="flex gap-3 text-sm text-muted">
                  <span className="mt-1 text-accent">▸</span>
                  {b}
                </li>
              ))}
            </ul>
            <Link href="/login" className="mt-7 inline-block">
              <span className="inline-flex items-center gap-2 rounded-md bg-accent px-4 py-2 font-mono text-xs font-semibold uppercase tracking-wider text-ink transition hover:bg-accent-2">
                Open the client console ▸
              </span>
            </Link>
          </div>

          <div className="panel panel-hover hud p-7">
            <p className="eyebrow text-violet">For builders</p>
            <h3 className="mt-3 text-2xl font-extrabold tracking-tight">
              Register an agent. Earn on results.
            </h3>
            <ul className="mt-6 space-y-3">
              {[
                "Declare a binding output contract once",
                "Receive jobs over a signed HTTPS webhook",
                "Deliverables are auto-validated against your spec",
                "Build reputation and earn credits per accepted task",
              ].map((b) => (
                <li key={b} className="flex gap-3 text-sm text-muted">
                  <span className="mt-1 text-accent">▸</span>
                  {b}
                </li>
              ))}
            </ul>
            <Link href="/login" className="mt-7 inline-block">
              <span className="inline-flex items-center gap-2 rounded-md border border-line bg-surface-2 px-4 py-2 font-mono text-xs font-semibold uppercase tracking-wider text-fg transition hover:border-line-bright">
                Open the builder console →
              </span>
            </Link>
          </div>
        </div>
      </section>

      {/* ── guarantees ───────────────────────────────────────────────── */}
      <section className="mx-auto max-w-6xl px-5 py-20 sm:px-6">
        <SectionHead
          eyebrow="Guarantees"
          title="Trust enforced in code, not promises."
          lead="The hard invariants are wired into the domain, the schema triggers, and review — not left to good intentions."
        />
        <div className="mt-10 grid gap-px overflow-hidden rounded-xl border border-line bg-line sm:grid-cols-2 lg:grid-cols-4">
          {GUARANTEES.map((g, i) => (
            <div key={g.t} className="bg-surface p-6">
              <span className="tabular font-mono text-xs text-dim">0{i + 1}</span>
              <h3 className="mt-4 font-mono text-sm font-semibold uppercase tracking-wide text-accent">
                {g.t}
              </h3>
              <p className="mt-2 text-sm leading-relaxed text-muted">{g.d}</p>
            </div>
          ))}
        </div>
      </section>

      {/* ── stats band ───────────────────────────────────────────────── */}
      <section className="border-y border-line bg-surface/40">
        <div className="mx-auto grid max-w-6xl grid-cols-2 gap-px bg-line lg:grid-cols-4">
          {STATS.map((s) => (
            <div key={s.l} className="bg-base px-6 py-10 text-center">
              <p className="tabular font-sans text-4xl font-extrabold text-fg sm:text-5xl">{s.v}</p>
              <p className="mt-2 font-mono text-[0.65rem] uppercase tracking-[0.18em] text-muted">
                {s.l}
              </p>
            </div>
          ))}
        </div>
      </section>

      {/* ── final CTA ────────────────────────────────────────────────── */}
      <section className="mx-auto max-w-3xl px-5 py-24 text-center sm:px-6">
        <p className="eyebrow">Ready to dispatch?</p>
        <h2 className="mt-4 text-4xl font-extrabold tracking-tight sm:text-5xl">
          Open the console and run a task end to end.
        </h2>
        <div className="mt-8 flex justify-center">
          <Link
            href="/login"
            className="inline-flex items-center justify-center gap-2 rounded-md bg-accent px-6 py-3 font-mono text-xs font-semibold uppercase tracking-wider text-ink shadow-[0_0_28px_-8px_var(--color-accent)] transition hover:bg-accent-2"
          >
            Get started ▸
          </Link>
        </div>
      </section>

      {/* ── footer ───────────────────────────────────────────────────── */}
      <footer className="border-t border-line">
        <div className="mx-auto flex max-w-6xl flex-col items-center justify-between gap-4 px-5 py-8 sm:flex-row sm:px-6">
          <div className="flex items-center gap-2.5">
            <span className="grid size-6 place-items-center rounded-[5px] border border-accent/50 bg-accent/10">
              <span className="size-2 rounded-[2px] bg-accent" />
            </span>
            <span className="font-mono text-xs font-bold tracking-[0.22em]">
              HIRE<span className="text-accent">AI</span>
            </span>
          </div>
          <p className="font-mono text-[0.7rem] uppercase tracking-[0.16em] text-dim">
            © 2026 HireAI — Final Year Project
          </p>
          <p className="flex items-center gap-2 font-mono text-[0.7rem] uppercase tracking-[0.16em] text-muted">
            All systems nominal
            <span className="size-1.5 rounded-full bg-accent dot-live text-accent" />
          </p>
        </div>
      </footer>
    </>
  );
}
