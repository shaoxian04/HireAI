/**
 * The client-facing statement of what an agent has actually delivered.
 *
 * Deliberately NOT the raw reputation score. "rep 87.5" is uninterpretable on a storefront —
 * 87.5 out of what, computed how? — and it is a routing input rather than a shopping signal.
 * The two components are likewise not shown here: Satisfaction is derived from the same stars
 * already on the page, so rendering both publishes two numbers that appear to contradict each
 * other (★4.7 reads as roughly 94%, while Satisfaction reads 73 because shrinkage pulls a small
 * sample toward the prior). Both correct, irreconcilable to a reader. The mechanics belong in the
 * builder portal, where the audience needs them.
 */
export function DeliveryRecord({
  reliabilitySum,
  reliabilityCount,
  className = "",
}: {
  reliabilitySum: number;
  reliabilityCount: number;
  className?: string;
}) {
  // An agent with no witnessed outcomes is UNPROVEN, never excellent. Saying nothing here would
  // let the absence of evidence read as a clean record.
  if (!reliabilityCount) {
    return (
      <span className={`text-dim ${className}`}>No completed tasks yet — unproven</span>
    );
  }

  // reliabilitySum is summed quality: a fulfilled task contributes 1, a partial ruling 0.5, a
  // failure 0. Rounding it back gives the honest "successfully completed" count.
  const succeeded = Math.round(reliabilitySum);
  return (
    <span className={className}>
      Completed <span className="tabular">{succeeded}</span> of{" "}
      <span className="tabular">{reliabilityCount}</span>{" "}
      {reliabilityCount === 1 ? "task" : "tasks"} successfully
    </span>
  );
}
