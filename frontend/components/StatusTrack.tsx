import { Fragment } from "react";
import type { TaskStatus } from "@/lib/types";
import { STAGES, taskStage } from "@/lib/taskStage";

/**
 * Compact instrument that plots a task's position along the SUBMIT→SETTLE money path.
 * Filled (cobalt) = passed, pulsing = current, amber = waiting, red = failed, dim = pending.
 */
export function StatusTrack({
  status,
  labels = false,
}: {
  status: TaskStatus;
  labels?: boolean;
}) {
  const { index, kind } = taskStage(status);

  function dot(i: number): { cls: string; live: boolean; text: string } {
    if (kind === "done" || i < index)
      return { cls: "bg-accent", live: false, text: "" };
    if (i === index) {
      if (kind === "fail")
        return { cls: "bg-red", live: false, text: "text-red" };
      if (kind === "wait")
        return { cls: "bg-amber", live: true, text: "text-amber" };
      return { cls: "bg-cyan", live: true, text: "text-cyan" };
    }
    return { cls: "bg-line-bright", live: false, text: "" };
  }

  const current = STAGES[Math.min(index, STAGES.length - 1)] ?? "";
  const stateWord =
    kind === "done"
      ? "complete"
      : kind === "fail"
        ? "failed at"
        : kind === "wait"
          ? "waiting at"
          : "at";
  const summary =
    kind === "done"
      ? "Progress: all stages complete"
      : `Progress: ${stateWord} ${current.toLowerCase()} (stage ${index + 1} of ${STAGES.length})`;

  const track = (
    <div className="flex items-start" role="img" aria-label={summary}>
      {STAGES.map((label, i) => {
        const d = dot(i);
        const passed = i < index || kind === "done";
        return (
          <Fragment key={label}>
            <div
              className={`flex flex-col items-center gap-2 ${labels ? "w-16" : ""}`}
            >
              <span
                className={`relative ${labels ? "size-3 ring-4 ring-surface" : "size-2"} rounded-full ${d.cls} ${d.live ? `dot-live ${d.text}` : ""}`}
              />
              {labels && (
                <span
                  className={`hidden text-xs sm:block ${i === index && kind !== "done" ? `font-bold ${d.text || "text-fg"}` : i < index || kind === "done" ? "font-medium text-fg" : "text-dim"}`}
                >
                  {label.charAt(0) + label.slice(1).toLowerCase()}
                </span>
              )}
            </div>
            {i < STAGES.length - 1 && (
              <span
                className={`relative flex-1 overflow-hidden bg-line-bright ${labels ? "mt-[5px] h-0.5 min-w-2" : "mt-[3px] h-px min-w-4"}`}
              >
                {passed && (
                  // fills left to right on load, one stage after another
                  <span
                    className="track-fill absolute inset-0 bg-accent"
                    style={{ animationDelay: `${i * 110}ms` }}
                  />
                )}
              </span>
            )}
          </Fragment>
        );
      })}
    </div>
  );

  if (!labels) return track;
  return (
    <div className="space-y-2">
      {track}
      <p aria-hidden className="text-xs font-semibold text-muted sm:hidden">
        {kind === "done"
          ? "All 6 stages complete"
          : `Stage ${index + 1} of ${STAGES.length}: ${current.charAt(0) + current.slice(1).toLowerCase()}`}
      </p>
    </div>
  );
}
