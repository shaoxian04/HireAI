import type { ReactNode } from "react";

/**
 * Renders the small Markdown subset clients actually type into task descriptions:
 * paragraphs, "-" / "*" bullet lists, "#" headings and **bold**. Everything is rendered
 * as React text nodes — never as HTML — so user input can't inject markup.
 */
export function RichText({ text, className = "" }: { text: string; className?: string }) {
  const blocks = text.replace(/\r\n/g, "\n").split(/\n{2,}/);
  return (
    <div className={`space-y-3 ${className}`}>
      {blocks.map((block, i) => {
        const lines = block.split("\n").filter((l) => l.trim() !== "");
        if (lines.length === 0) return null;
        if (lines.every((l) => /^\s*[-*]\s+/.test(l))) {
          return (
            <ul key={i} className="list-disc space-y-1 pl-5 marker:text-dim">
              {lines.map((l, j) => (
                <li key={j}>{inline(l.replace(/^\s*[-*]\s+/, ""))}</li>
              ))}
            </ul>
          );
        }
        const heading = /^#{1,6}\s+(.*)$/.exec(lines[0]!);
        if (heading && lines.length === 1) {
          return (
            <p key={i} className="font-bold text-fg">
              {inline(heading[1]!)}
            </p>
          );
        }
        return (
          <p key={i} className="whitespace-pre-wrap">
            {inline(lines.join("\n"))}
          </p>
        );
      })}
    </div>
  );
}

/** `**bold**` → <strong>; everything else stays plain text. */
function inline(s: string): ReactNode[] {
  return s.split(/(\*\*[^*]+\*\*)/g).map((part, i) =>
    part.startsWith("**") && part.endsWith("**") && part.length > 4 ? (
      <strong key={i} className="font-semibold text-fg">
        {part.slice(2, -2)}
      </strong>
    ) : (
      part
    ),
  );
}
