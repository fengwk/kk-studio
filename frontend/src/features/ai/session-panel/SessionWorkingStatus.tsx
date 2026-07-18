/** Pi-style working strip between dialogue and input (statusContainer). */
export function SessionWorkingStatus({ active }: { active: boolean }) {
  if (!active) {
    return null
  }
  return (
    <div className="session-working" aria-live="polite">
      <span className="session-working-dot" aria-hidden="true" />
      <span>Working...</span>
    </div>
  )
}
