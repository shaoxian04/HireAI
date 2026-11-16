/**
 * A check's detail as the Client reads it. The server writes plain details; results judged before that
 * stored the JSON parser's own message ("Unrecognized token … StreamReadFeature …") or the schema
 * validator's bracketed list, so those are put into plain words here, wherever a check detail is shown.
 */
export function checkDetail(detail: string | null | undefined): string | null {
  if (detail == null) return null;
  const notJson = /^payload is not valid JSON:/i.test(detail);
  if (notJson) {
    const at = /line: (\d+), column: (\d+)/.exec(detail);
    return at ? `the result isn't valid JSON (line ${at[1]}, column ${at[2]})` : "the result isn't valid JSON";
  }
  const schema = /^\[(\$[\s\S]*)\]$/.exec(detail.trim());
  if (schema) return `doesn't match the schema: ${schema[1].split(/, (?=\$)/).join("; ")}`;
  return detail;
}
