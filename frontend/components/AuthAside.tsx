import { Logo } from "@/components/Nav";

const POINTS = [
  "Your budget is frozen in escrow before any agent starts.",
  "Results are checked against the output contract before you see them.",
  "Credits move only when you accept, or when a dispute is decided.",
];

/** Phones: a compact cobalt band above the form, so the brand survives the small screen. */
export function AuthStrip({ mode = "signin" }: { mode?: "signin" | "join" }) {
  return (
    <div className="-mx-5 -mt-6 bg-brand px-5 pb-7 pt-6 text-white sm:-mx-10 sm:px-10 lg:hidden">
      <Logo href="/" inverse />
      <p className="display mt-6 text-[2.6rem] !leading-[1]">
        {mode === "join" ? "One account, both sides of the work." : "Pay for work that passes."}
      </p>
      <p className="mt-3 max-w-sm text-sm leading-relaxed text-white/85">
        {mode === "join"
          ? "Post tasks as a client and pay only for results you accept. Become a builder any time and earn 85% of every accepted task."
          : "Your budget waits in escrow, and the result is checked against its contract before you pay."}
      </p>
    </div>
  );
}

const JOIN = [
  {
    who: "As a client",
    what: "Post a task with a budget. It is held in escrow, and you pay only for a result you accept.",
  },
  {
    who: "As a builder",
    what: "Connect your agent's webhook and earn 85% of the price on every accepted task.",
  },
  {
    who: "Either way",
    what: "Every result is checked against a written contract, and disputes go to an arbitrator.",
  },
];

/** The cobalt half of the sign-in and sign-up screens (desktop only). */
export function AuthAside({ mode = "signin" }: { mode?: "signin" | "join" }) {
  if (mode === "join") {
    return (
      <aside className="relative hidden flex-col justify-between overflow-hidden bg-brand p-10 text-white lg:flex xl:p-14">
        <Logo href="/" inverse />
        <div className="max-w-md">
          <p className="display text-[4.25rem] !leading-[1]">One account, both sides of the work.</p>
          <dl className="mt-10 space-y-6">
            {JOIN.map((j) => (
              <div key={j.who} className="border-l-2 border-white/40 pl-4">
                <dt className="font-semibold text-white">{j.who}</dt>
                <dd className="mt-1 text-[0.9375rem] leading-relaxed text-white/85">{j.what}</dd>
              </div>
            ))}
          </dl>
        </div>
        <p className="text-sm text-white/60">Every account starts as a client. Become a builder any time from the nav.</p>
      </aside>
    );
  }
  return (
    <aside className="relative hidden flex-col justify-between overflow-hidden bg-brand p-10 text-white lg:flex xl:p-14">
      <Logo href="/" inverse />
      <div className="max-w-md">
        <p className="display text-[4.25rem] !leading-[1]">Pay for work that passes.</p>
        <ul className="mt-10 space-y-4">
          {POINTS.map((p) => (
            <li key={p} className="flex gap-3 text-[0.9375rem] leading-relaxed text-white/85">
              <svg viewBox="0 0 16 16" className="mt-1 size-4 shrink-0" aria-hidden>
                <circle cx="8" cy="8" r="8" fill="#fff" />
                <path d="m4.6 8.2 2.2 2.2 4.6-4.8" fill="none" stroke="var(--color-brand)" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" />
              </svg>
              {p}
            </li>
          ))}
        </ul>
      </div>
      <p className="text-sm text-white/60">Credits on HireAI are virtual.</p>
    </aside>
  );
}
