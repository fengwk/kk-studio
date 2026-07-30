import { useMemo } from 'react'
import { useQuery } from '@tanstack/react-query'
import { filterEnvironments } from '@/features/ai/environment/environment-utils'
import { StateBlock } from '@/features/ai/shared/AiConsoleCommonCards'
import { environmentService } from '@/shared/api/environment-service'
import { NavigationSlot } from '@/platform/workbench/WorkbenchSlots'
import { queryKeys } from '@/shared/lib/query-keys'

function formatDateTime24(date: Date): string {
  return date.toLocaleString('zh-CN', {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
    hour12: false,
  })
}

function formatLastSeen(value: string | number | null | undefined): string {
  if (value == null || value === '') {
    return ''
  }
  if (typeof value === 'number' && Number.isFinite(value)) {
    const ms = value < 1e12 ? value * 1000 : value
    return formatDateTime24(new Date(ms))
  }
  const raw = String(value).trim()
  if (!raw) {
    return ''
  }
  // numeric epoch seconds / millis as string
  if (/^\d+(\.\d+)?$/.test(raw)) {
    const n = Number(raw)
    if (Number.isFinite(n)) {
      const ms = n < 1e12 ? n * 1000 : n
      return formatDateTime24(new Date(ms))
    }
  }
  const parsed = Date.parse(raw)
  if (Number.isFinite(parsed)) {
    return formatDateTime24(new Date(parsed))
  }
  return raw
}

function TagRow({ label, names, limit = 3 }: { label: string; names: string[]; limit?: number }) {
  const clean = names.map((name) => name.trim()).filter(Boolean)
  const visible = clean.slice(0, limit)
  const rest = clean.length - visible.length

  return (
    <div className="meta-row env-tag-row">
      <span className="lbl">{label}</span>
      {clean.length === 0 ? (
        <span className="val val-empty" />
      ) : (
        <div className="meta-chips meta-chips-single" title={clean.join(', ')}>
          {visible.map((name) => (
            <span key={name} className="meta-chip">
              {name}
            </span>
          ))}
          {rest > 0 ? <span className="meta-chip is-more">+{rest}</span> : null}
        </div>
      )}
    </div>
  )
}

export function EnvironmentsPage() {
  const environmentsQuery = useQuery({
    queryKey: queryKeys.environments.list,
    queryFn: () => environmentService.listEnvironments(),
    refetchInterval: 10_000,
  })

  // Natural name order so live environments are easy to scan.
  const environments = useMemo(
    () => filterEnvironments(environmentsQuery.data ?? [], ''),
    [environmentsQuery.data],
  )

  return (
    <section className="screen active">
      <nav className="subbar">
        <NavigationSlot />
      </nav>
      <div className="screen-body">
        {environmentsQuery.isLoading && <StateBlock title="正在加载 Environments" />}
        {environmentsQuery.error && (
          <StateBlock
            title={environmentsQuery.error instanceof Error ? environmentsQuery.error.message : '加载失败'}
            tone="danger"
          />
        )}
        {!environmentsQuery.isLoading && !environmentsQuery.error && (
          <div className="cards-grid environment-list">
            {environments.length === 0 ? (
              <StateBlock title="当前没有 live Environment" />
            ) : (
              environments.map((environment) => {
                const status = String(environment.status).toUpperCase()
                const ready = status === 'READY'
                const toolNames = (environment.tools ?? []).map((tool) => tool.name).filter(Boolean)
                const skillNames = (environment.skills ?? []).map((skill) => skill.name).filter(Boolean)
                const lastSeen = formatLastSeen(environment.lastSeen)
                return (
                  <article key={environment.name} className="info-card environment-card">
                    <div className="head">
                      <div className="head-content">
                        <div className="text-content">
                          <h3 title={environment.name}>{environment.name}</h3>
                          <p title={lastSeen || undefined}>
                            {lastSeen ? `Last seen · ${lastSeen}` : 'Last seen'}
                          </p>
                        </div>
                        <span className={`status-pill${ready ? ' is-ready' : ' is-offline'}`}>{status}</span>
                      </div>
                    </div>
                    <div className="meta-block">
                      <TagRow label="Tools" names={toolNames} />
                      <TagRow label="Skills" names={skillNames} />
                    </div>
                  </article>
                )
              })
            )}
          </div>
        )}
      </div>
    </section>
  )
}
