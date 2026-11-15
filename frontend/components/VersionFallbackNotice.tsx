import type { VersionFallbackDTO } from "@/lib/types";

/**
 * Tells the Client that their terms use the previous Template version (spec D36): a new version was
 * published, no agent accepts it yet, and during the transition the platform falls back to the old one.
 * `context` picks the sentence: the search (preview), the booking about to be made, or the booked task.
 */
interface Props {
  fallback: VersionFallbackDTO;
  /** The Template's plain title; the key is used when it isn't known. */
  templateTitle?: string;
  context: "preview" | "booking" | "task";
}

const DATE = new Intl.DateTimeFormat("en-GB", { day: "numeric", month: "short", year: "numeric", timeZone: "UTC" });

export function VersionFallbackNotice({ fallback, templateTitle, context }: Props) {
  const current = `${fallback.template}@${fallback.currentVersion}`;
  const used = `${fallback.template}@${fallback.usedVersion}`;
  const until = DATE.format(new Date(fallback.transitionEndsAt));
  const name = templateTitle ?? fallback.template;
  return (
    <div role="status" className="rounded-md border border-amber/30 bg-amber/10 px-3 py-2.5 text-sm text-fg">
      <p className="font-semibold">
        {context === "task" ? (
          <>
            This task is held to <Ref>{used}</Ref>, the previous version
          </>
        ) : (
          <>No agent accepts the new version of {name} yet</>
        )}
      </p>
      <p className="mt-1 text-xs leading-relaxed text-muted">
        {context === "task" ? (
          <>
            No agent accepted the new version of {name} (<Ref>{current}</Ref>) when it was booked. It stays
            judged under <Ref>{used}</Ref>, which takes new bookings until {until}.
          </>
        ) : (
          <>
            Your terms are matched under the previous version, <Ref>{used}</Ref>, instead of{" "}
            <Ref>{current}</Ref>. The previous version takes new bookings until {until}.
            {context === "booking" && (
              <>
                {" "}
                Booking holds the agent to <Ref>{used}</Ref>.
              </>
            )}
          </>
        )}
      </p>
    </div>
  );
}

function Ref({ children }: { children: string }) {
  return <span className="font-mono text-[0.8125rem] text-fg">{children}</span>;
}
