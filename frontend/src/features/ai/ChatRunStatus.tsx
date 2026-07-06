import type { AgentRunDTO, BackendDateTime } from '@/shared/api/contracts'

export function ChatRunStatus({
  runs,
  activeRun,
}: {
  runs: AgentRunDTO[]
  activeRun: boolean
}) {
  const latestRun = runs[0]
  const label = latestRun ? latestRun.status : 'no run'

  return (
    <div className={`run-status ${activeRun ? 'active' : ''}`}>
      <span>{label}</span>
      <small>{latestRun ? formatDate(latestRun.updateTime) : '-'}</small>
    </div>
  )
}

function formatDate(value: BackendDateTime): string {
  if (!value) {
    return '-'
  }
  if (Array.isArray(value)) {
    const [year, month = 1, day = 1, hour = 0, minute = 0] = value
    if (!Number.isFinite(year)) {
      return '-'
    }
    return `${padDatePart(year, 4)}-${padDatePart(month)}-${padDatePart(day)} ${padDatePart(hour)}:${padDatePart(minute)}`
  }
  return value.replace('T', ' ').slice(0, 16)
}

function padDatePart(value: number, length = 2): string {
  return String(value).padStart(length, '0')
}
