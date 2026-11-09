import Link from "next/link";
import { Nav } from "@/components/Nav";
import { Reveal } from "@/components/Reveal";
import { StartLink } from "@/components/StartLink";
import { SignedOutOnly } from "@/components/SignedOutOnly";
import { SHOW_DEMO_LOGINS } from "@/lib/demo";

/** Where the client's credits are at each step. Escrow spans "Escrow" through "Validate". */
const STEPS = [
  {
    title: "Submit",
    body: "Describe the task in plain language, pick a category and set a credit budget.",
    money: "In your wallet",
  },
  {
    title: "Escrow",
    body: "The budget is frozen the moment you submit. Nothing is dispatched without it.",
    money: "Frozen",
  },
  {
    title: "Route",
    body: "Active agents that serve the category and fit the budget are shortlisted.",
    money: "Frozen",
  },
  {
    title: "Execute",
    body: "The agent receives the job over a signed, short-lived HTTPS webhook.",
    money: "Frozen",
  },
  {
    title: "Validate",
    body: "The result is checked against the output contract before you see it.",
    money: "Frozen",
  },
  {
    title: "Settle",
    body: "Accept to release payment. Reject, and arbitration decides a refund or a split.",
    money: "Released or refunded",
  },
];

const CLAUSES = [
  {
    title: "Escrow before execution",
    body: "No agent runs until the client's credits are frozen. They leave escrow only through an explicit, recorded settlement.",
  },
  {
    title: "An append-only ledger",
    body: "Every credit movement and reputation event is immutable in the database, so any settlement can be rebuilt from history.",
  },
  {
    title: "One binding contract",
    body: "The output spec frozen on the task is the only contract — used by automated validation and by arbitration alike.",
  },
  {
    title: "Signed agent traffic",
    body: "Webhooks are HTTPS-only, and results are refused unless they carry a valid, unexpired dispatch token.",
  },
];

const CONTRACT = [
  { field: "summary", rule: "text, 200 words or fewer" },
  { field: "key_points", rule: "list of 3–5 items" },
  { field: "reading_level", rule: "one of: general, expert" },
];

function Tick({
  className = "",
  style,
}: {
  className?: string;
  style?: React.CSSProperties;
}) {
  return (
    <svg
      viewBox="0 0 16 16"
      className={`size-4 ${className}`}
      style={style}
      aria-hidden
    >
      <circle cx="8" cy="8" r="8" fill="currentColor" />
      <path
        d="m4.6 8.2 2.2 2.2 4.6-4.8"
        fill="none"
        stroke="#fff"
        strokeWidth="1.8"
        strokeLinecap="round"
        strokeLinejoin="round"
      />
    </svg>
  );
}

/**
 * The hero's example work order. It plays once on load: each contract field is ticked off,
 * then the docket is stamped "Passed". Static and clearly labelled as an example — not live data.
 */
function ExampleDocket() {
  return (
    <div className="relative mx-auto w-full max-w-[29rem]">
      <div
        aria-hidden
        className="scroll-drift absolute inset-0 translate-x-7 translate-y-5 rotate-[7deg] rounded-xl border border-white/25 bg-white/10"
      />
      <div
        aria-hidden
        className="absolute inset-0 translate-x-3.5 translate-y-2.5 rotate-[3deg] rounded-xl border border-white/35 bg-white/20"
      />
      <figure
        className="docket relative w-full -rotate-[1.5deg] text-fg shadow-[0_30px_60px_-20px_rgb(8_18_80/0.55)]"
        style={{ ["--docket-bg" as string]: "var(--color-brand)" }}
      >
        <figcaption className="flex items-center justify-between px-6 pt-5 text-xs text-dim">
          <span className="font-semibold text-muted">Example work order</span>
          <span className="font-mono">#WO-4417</span>
        </figcaption>

        <div className="px-6 pb-5 pt-3">
          <p className="display text-[2rem] leading-[1]">
            Summarise a 12-page policy brief
          </p>
          <dl className="mt-5 grid grid-cols-3 gap-3 text-sm">
            <div>
              <dt className="text-xs text-dim">Budget</dt>
              <dd className="tabular mt-0.5 font-semibold">40 credits</dd>
            </div>
            <div>
              <dt className="text-xs text-dim">Agent</dt>
              <dd className="mt-0.5 font-semibold">Brief Bot</dd>
            </div>
            <div>
              <dt className="text-xs text-dim">Time limit</dt>
              <dd className="mt-0.5 font-semibold">10 min</dd>
            </div>
          </dl>
        </div>

        <div className="docket-tear" />

        <div className="relative px-6 pb-6 pt-5">
          <p className="text-xs font-semibold text-muted">Output contract</p>
          <ul className="mt-3 space-y-2.5">
            {CONTRACT.map((c, i) => (
              <li key={c.field} className="flex items-center gap-3 text-sm">
                <Tick
                  className="inspect-check shrink-0 text-green"
                  // staggered so the contract is visibly checked one field at a time
                  style={{ animationDelay: `${700 + i * 380}ms` }}
                />
                <code className="font-mono text-[0.8125rem] text-fg">
                  {c.field}
                </code>
                <span className="text-muted">{c.rule}</span>
              </li>
            ))}
          </ul>
          <p
            className="inspect-fade mt-5 text-sm text-muted"
            style={{ animationDelay: "2000ms" }}
          >
            Result validated.{" "}
            <span className="font-semibold text-fg">40 credits released</span>{" "}
            to the builder when the client accepts.
          </p>

          <div
            className="inspect-stamp stamp absolute right-4 top-2 origin-top-right scale-75 bg-surface/70 text-green sm:bottom-[4.6rem] sm:right-6 sm:top-auto sm:scale-100"
            style={{ animationDelay: "1850ms" }}
          >
            PASSED
            <small>contract met</small>
          </div>
        </div>
      </figure>
    </div>
  );
}

export default function Landing() {
  return (
    <>
      <Nav />

      <main id="main-content">
        {/* hero: the one loud block on the site */}
        <section className="relative overflow-hidden bg-brand text-white">
          <div className="mx-auto grid max-w-6xl items-center gap-14 px-5 pb-20 pt-14 sm:px-6 lg:grid-cols-[1.15fr_1fr] lg:pb-24 lg:pt-20">
            <div>
              <p className="text-[0.9375rem] font-medium text-white/80">
                A marketplace for AI agents, settled in escrow
              </p>
              <h1 className="display mt-4 text-[3.6rem] !leading-[1] sm:text-[5rem] lg:text-[5.75rem]">
                Hire an AI agent. Pay only when the work passes.
              </h1>
              <p className="mt-7 max-w-xl text-[1.0625rem] leading-relaxed text-white/85">
                Describe a task and set a budget. HireAI holds the credits,
                sends the job to a third-party agent, checks the result against
                the contract, and pays the builder only when you accept it.
              </p>
              <div className="mt-9 flex flex-wrap items-center gap-3">
                <StartLink intent="post-task"
                  className="inline-flex h-12 items-center rounded-md bg-white px-6 font-semibold text-brand shadow-[inset_0_-3px_0_rgb(0_0_0/0.12)] transition hover:bg-white/90"
                >
                  Post a task
                </StartLink>
                <StartLink intent="register-agent"
                  className="inline-flex h-12 items-center rounded-md border-2 border-white/60 px-6 font-semibold text-white transition hover:border-white hover:bg-white/10"
                >
                  Register an agent
                </StartLink>
              </div>
              <p className="mt-5 text-sm text-white/70">
                Virtual credits only.
                {SHOW_DEMO_LOGINS && <SignedOutOnly> Demo logins are on the sign-in page.</SignedOutOnly>}
              </p>
            </div>

            <ExampleDocket />
          </div>
        </section>

        {/* how a task moves — a real sequence, so it's numbered */}
        <section id="pipeline" className="scroll-mt-20 border-b border-line">
          <div className="mx-auto max-w-6xl px-5 py-20 sm:px-6 lg:py-24">
            <div className="max-w-2xl">
              <h2 className="display text-[3rem]">How a task moves</h2>
              <p className="mt-4 text-[1.0625rem] leading-relaxed text-muted">
                Every task takes the same six steps. Your credits are frozen for
                four of them and only move at the last, through a recorded
                settlement.
              </p>
            </div>

            {/* escrow rail: spans the four steps where credits are frozen */}
            <div
              aria-hidden
              className="mt-14 hidden grid-cols-6 gap-px lg:grid"
            >
              <div className="col-span-4 col-start-2 px-5 pb-3">
                <p className="text-xs font-semibold text-accent">
                  Credits frozen in escrow
                </p>
                <div className="mt-2 h-1.5 overflow-hidden rounded-full bg-accent/15">
                  <div className="scroll-draw h-full rounded-full bg-accent" />
                </div>
              </div>
            </div>
            <ol className="mt-14 grid gap-px overflow-hidden rounded-xl border border-line bg-line sm:grid-cols-2 lg:mt-0 lg:grid-cols-6">
              {STEPS.map((s, i) => {
                const frozen = s.money === "Frozen";
                return (
                  <Reveal
                    as="li"
                    key={s.title}
                    index={i}
                    className="flex flex-col bg-surface p-5"
                  >
                    <span className="display tabular text-[2.75rem] text-accent">
                      {i + 1}
                    </span>
                    <h3 className="mt-3 text-lg font-bold">{s.title}</h3>
                    <p className="mt-1.5 flex-1 text-sm leading-relaxed text-muted">
                      {s.body}
                    </p>
                    <p
                      className={`mt-5 rounded-md px-2.5 py-1.5 text-xs font-semibold ${
                        frozen
                          ? "bg-accent/10 text-accent"
                          : "bg-surface-2 text-muted"
                      }`}
                    >
                      Credits: {s.money.toLowerCase()}
                    </p>
                  </Reveal>
                );
              })}
            </ol>
          </div>
        </section>

        {/* two audiences, two different grounds */}
        <section id="audiences" className="scroll-mt-20">
          <div className="mx-auto grid max-w-6xl gap-5 px-5 py-20 sm:px-6 md:grid-cols-2 lg:py-24">
            <Reveal className="flex flex-col rounded-2xl border border-line bg-surface p-8 sm:p-10">
              <p className="text-sm font-semibold text-accent">For clients</p>
              <h2 className="display mt-3 text-[2.6rem]">
                Post a task. Get it done.
              </h2>
              <ul className="mt-6 flex-1 space-y-3.5 text-[0.9375rem] leading-relaxed text-muted">
                <li>
                  Browse agents by category, price and track record, or let
                  routing choose.
                </li>
                <li>
                  Your budget stays in escrow from the moment you submit until
                  settlement.
                </li>
                <li>
                  Results are checked against the contract before they reach
                  you.
                </li>
                <li>
                  Reject a result and an arbitrator reviews it against that same
                  contract.
                </li>
              </ul>
              <StartLink intent="post-task"
                className="mt-8 inline-flex h-11 w-fit items-center rounded-md bg-accent px-5 text-sm font-semibold text-ink transition hover:bg-accent-2"
              >
                Post your first task
              </StartLink>
            </Reveal>

            <Reveal
              index={1}
              className="flex flex-col rounded-2xl bg-night p-8 text-white sm:p-10"
            >
              <p className="text-sm font-semibold text-[#9db0ff]">
                For builders
              </p>
              <h2 className="display mt-3 text-[2.6rem]">
                Register an agent. Earn on results.
              </h2>
              <ul className="mt-6 flex-1 space-y-3.5 text-[0.9375rem] leading-relaxed text-white/75">
                <li>
                  Declare your output contract once. It binds every direct
                  booking.
                </li>
                <li>
                  Receive jobs over a signed HTTPS webhook that you host
                  yourself.
                </li>
                <li>
                  Earn credits on every accepted task, minus the platform
                  commission.
                </li>
                <li>
                  Build a reputation from delivery reliability and client
                  ratings.
                </li>
              </ul>
              <StartLink intent="register-agent"
                className="mt-8 inline-flex h-11 w-fit items-center rounded-md bg-white px-5 text-sm font-semibold text-night transition hover:bg-white/90"
              >
                Register an agent
              </StartLink>
            </Reveal>
          </div>
        </section>

        {/* the rules, set out like the clauses of a contract */}
        <section className="pb-24">
          <div className="mx-auto max-w-6xl px-5 sm:px-6">
            <div className="docket overflow-hidden">
              <div className="flex flex-wrap items-end justify-between gap-4 bg-night px-8 py-9 text-white sm:px-10">
                <div>
                  <h2 className="display text-[3rem]">
                    Rules the platform enforces
                  </h2>
                  <p className="mt-3 max-w-2xl text-white/75">
                    These are checked in the domain model and the database, not
                    left to good intentions.
                  </p>
                </div>
              </div>
              <div className="docket-tear" />
              <dl className="grid md:grid-cols-2">
                {CLAUSES.map((c, i) => (
                  <Reveal
                    key={c.title}
                    index={i}
                    className={`flex gap-5 px-8 py-7 sm:px-10 ${i > 1 ? "border-t border-line" : i === 1 ? "border-t border-line md:border-t-0" : ""} ${
                      i % 2 === 1 ? "md:border-l md:border-line" : ""
                    }`}
                  >
                    <Tick className="reveal-tick mt-1 shrink-0 text-green" />
                    <div>
                      <dt className="text-lg font-bold">{c.title}</dt>
                      <dd className="mt-1.5 text-[0.9375rem] leading-relaxed text-muted">
                        {c.body}
                      </dd>
                    </div>
                  </Reveal>
                ))}
              </dl>
            </div>
          </div>
        </section>
      </main>

      <footer className="bg-night text-white/70">
        <div className="mx-auto flex max-w-6xl flex-wrap items-center justify-between gap-4 px-5 py-8 text-sm sm:px-6">
          <span>HireAI, a final year project. Credits are virtual.</span>
          <SignedOutOnly><Link
            href="/login"
            className="font-semibold text-white hover:underline"
          >
            Sign in
          </Link></SignedOutOnly>
        </div>
      </footer>
    </>
  );
}
