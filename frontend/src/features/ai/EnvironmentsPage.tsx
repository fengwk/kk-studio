import { useQuery } from '@tanstack/react-query'
import { StateBlock } from '@/features/ai/AiConsoleCards'
import { environmentService } from '@/shared/api/environment-service'
import { NavigationSlot } from '@/platform/workbench/WorkbenchSlots'
import { queryKeys } from '@/shared/lib/query-keys'

function formatLastSeen(value: string | null): string {
  if (!value) {
    return '-'
  }
  const parsed = Date.parse(value)
  if (!Number.isFinite(parsed)) {
    return value
  }
  return new Date(parsed).toLocaleString()
}

export function EnvironmentsPage() {
  const environmentsQuery = useQuery({
    queryKey: queryKeys.environments.list,
    queryFn: () => environmentService.listEnvironments(),
    refetchInterval: 10_000,
  })

  const environments = environmentsQuery.data ?? []

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
          <div className="environment-list">
            {environments.length === 0 ? (
              <StateBlock title="当前没有 live Environment" />
            ) : (
              environments.map((environment) => {
                const status = String(environment.status).toUpperCase()
                const ready = status === 'READY'
                return (
                  <article key={environment.name} className="info-card environment-card">
                    <div className="head">
                      <div className="text-content">
                        <h3>{environment.name}</h3>
                        <p className={ready ? 'status-ready' : 'status-offline'}>
                          {status}
                        </p>
                      </div>
                    </div>
                    <div className="meta-block">
                      <div className="meta-row">
                        <span className="lbl">Last seen</span>
                        <span className="val">{formatLastSeen(environment.lastSeen)}</span>
                      </div>
                      <div className="meta-row">
                        <span className="lbl">Tools</span>
                        <span className="val">
                          {(environment.tools ?? []).map((tool) => tool.name).join(', ') || '（无）'}
                        </span>
                      </div>
                      <div className="meta-row">
                        <span className="lbl">Skills</span>
                        <span className="val">
                          {(environment.skills ?? []).map((skill) => skill.name).join(', ') || '（无）'}
                        </span>
                      </div>
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
