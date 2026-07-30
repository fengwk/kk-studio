/** Pi-style working strip between dialogue and input (statusContainer). */
export function ThreadWorkingStatus({ active, label = 'Working...' }: { active: boolean; label?: string }) {
  if (!active) {
    return null
  }
  return (
    <div className="thread-working" aria-live="polite">
      <span className="thread-working-dot" aria-hidden="true" />
      <span>{label}</span>
    </div>
  )
}
