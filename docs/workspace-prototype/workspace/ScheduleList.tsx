import { ChevronDown, ChevronLeft, ChevronRight, MoreHorizontal } from 'lucide-react'
import { Button } from '@/shared/ui/controls/Button'
import { IconButton } from '@/shared/ui/controls/IconButton'

type ScheduleTime = { dateTime: string; label: string; title: string }
type ScheduleRecord = {
  id: string
  time: ScheduleTime
} & ({ thread: string; reason?: never } | { thread?: never; reason: string })

type ScheduleRow = {
  id: string
  name: string
  enabled: boolean
  next?: ScheduleTime
  history?: { records: ScheduleRecord[]; hasNewer: boolean; hasOlder: boolean }
}

export function ScheduleList({ rows, onToggle }: { rows: ScheduleRow[]; onToggle: (id: string, enabled: boolean) => void }) {
  if (!rows.length) return <div className="empty x-empty"><h3>还没有定时任务</h3></div>
  return rows.map(({ id, name, enabled, next, history }) => <div key={id} className={`x-sch${enabled ? '' : ' x-off'}`} data-sch={id}>
    <div className="x-sch-heading">
      <span className="x-sch-main" title={name} data-allow-truncate>{name}</span>
      <label className="switch x-sch-sw">
        <input type="checkbox" role="switch" checked={enabled} onChange={(event) => onToggle(id, event.currentTarget.checked)}
          aria-label={`启用「${name}」`} />
      </label>
      <IconButton size="compact" className="x-ib" label={`「${name}」的更多操作`} title="更多操作"
        data-action="sch-menu" aria-haspopup="menu" aria-expanded={false} aria-controls="x-menu">
        <MoreHorizontal className="x-ic" aria-hidden="true" />
      </IconButton>
    </div>
    <div className="x-sch-meta">
      {next && <time className="x-sch-next" dateTime={next.dateTime} title={next.title}>下次 {next.label}</time>}
      <Button variant="ghost" size="compact" className="x-sch-disclosure" aria-label={`「${name}」的执行记录`}
        data-action="sch-history" aria-expanded={!!history} aria-controls={`sch-history-${id}`}>
        执行记录<ChevronDown className="x-ic" aria-hidden="true" />
      </Button>
    </div>
    <div className="x-sch-history" id={`sch-history-${id}`} hidden={!history}>
      {history && (history.records.length ? <>
        <ul className="x-sch-records" role="list" aria-label={`「${name}」的执行记录`}>
          {history.records.map(({ id: runId, time, thread, reason }) => {
            const content = <>
              <time dateTime={time.dateTime} title={time.title}>{time.label}</time>
              <span className="x-sch-record-text" title={thread ?? reason} data-allow-truncate>{thread ?? reason}</span>
            </>
            return <li key={runId}>
              {thread ? <Button variant="ghost" className="x-sch-record" data-action="sch-open-run"
                data-run={runId} data-fkey={`sch-run:${runId}`}>
                {content}<ChevronRight className="x-ic" aria-hidden="true" />
              </Button> : <div className="x-sch-record">{content}</div>}
            </li>
          })}
        </ul>
        {(history.hasNewer || history.hasOlder) && <div className="x-sch-pager">
          <Button variant="ghost" size="compact" data-action="sch-newer" data-fkey={`sch-newer:${id}`} disabled={!history.hasNewer}>
            <ChevronLeft className="x-ic" aria-hidden="true" />较新
          </Button>
          <Button variant="ghost" size="compact" data-action="sch-older" data-fkey={`sch-older:${id}`} disabled={!history.hasOlder}>
            较早<ChevronRight className="x-ic" aria-hidden="true" />
          </Button>
        </div>}
      </> : <p className="x-sch-empty">还没有执行记录</p>)}
    </div>
  </div>)
}
