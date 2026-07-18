/** Pi-style working strip between dialogue and input (statusContainer). */
export function ThreadWorkingStatus({ active }: { active: boolean }) {
  if (!active) {
    return null
  }
  return (
    <div className="thread-working" aria-live="polite">
      <span className="thread-working-dot" aria-hidden="true" />
      <span>Working...</span>
    </div>
  )
}
