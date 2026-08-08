import { useMemo } from 'react'
import { useQuery } from '@tanstack/react-query'
import { filterEnvironments } from '@/features/ai/environment/environment-utils'
import { StateBlock } from '@/shared/ui/console/AiConsoleCommonCards'
import { environmentService } from '@/shared/api/environment-service'
import { NavigationSlot } from '@/platform/workbench/WorkbenchSlots'
import { queryKeys } from '@/shared/lib/query-keys'
import { useI18n, type AppLocale } from '@/shared/i18n'
import type { LiveEnvironmentMcpServerDTO } from '@/shared/api/contracts/ai-environment'

function formatDateTime24(date: Date, locale: AppLocale): string {
  return date.toLocaleString(locale, {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
    hour12: false,
  })
}

function formatLastSeen(value: string | number | null | undefined, locale: AppLocale): string {
  if (value == null || value === '') {
    return ''
  }
  if (typeof value === 'number' && Number.isFinite(value)) {
    const ms = value < 1e12 ? value * 1000 : value
    return formatDateTime24(new Date(ms), locale)
  }
  const raw = String(value).trim()
  if (!raw) {
    return ''
  }
  // 数字形式的 epoch 秒或毫秒字符串
  if (/^\d+(\.\d+)?$/.test(raw)) {
    const n = Number(raw)
    if (Number.isFinite(n)) {
      const ms = n < 1e12 ? n * 1000 : n
      return formatDateTime24(new Date(ms), locale)
    }
  }
  const parsed = Date.parse(raw)
  if (Number.isFinite(parsed)) {
    return formatDateTime24(new Date(parsed), locale)
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

/** MCP server 摘要行：状态 + 限长错误 + 工具名摘要；只读展示，不作为可选 Agent 工具。 */
function McpServerRow({
  server,
  t,
}: {
  server: LiveEnvironmentMcpServerDTO
  t: (key: string) => string
}) {
  const ready = server.status === 'READY'
  const toolNames = (server.tools ?? []).map((tool) => tool.name).filter(Boolean)
  return (
    <div className="meta-row env-tag-row env-mcp-row">
      <span className="lbl">{t('ai.environment.mcpServers')}</span>
      <div className="meta-chips env-mcp-server">
        <span className="meta-chip">{server.name}</span>
        <span className={`status-pill is-mcp${ready ? ' is-ready' : ' is-offline'}`}>{server.status}</span>
        {server.error ? (
          <span className="val val-muted" title={server.error}>
            {server.error}
          </span>
        ) : null}
        {toolNames.length > 0 ? (
          <span className="val" title={toolNames.join(', ')}>
            {toolNames.join(', ')}
          </span>
        ) : null}
      </div>
    </div>
  )
}

export function EnvironmentsPage() {
  const { t, locale } = useI18n()
  const environmentsQuery = useQuery({
    queryKey: queryKeys.environments.list,
    queryFn: () => environmentService.listEnvironments(),
    refetchInterval: 10_000,
  })

  // 按名称自然序排列，便于扫描在线 Environment。
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
        {environmentsQuery.isLoading && <StateBlock title={t('ai.environment.loading')} />}
        {environmentsQuery.error && (
          <StateBlock
            title={
              environmentsQuery.error instanceof Error
                ? environmentsQuery.error.message
                : t('ai.environment.loadFailed')
            }
            tone="danger"
          />
        )}
        {!environmentsQuery.isLoading && !environmentsQuery.error && (
          <div className="cards-grid environment-list">
            {environments.length === 0 ? (
              <StateBlock title={t('ai.environment.empty')} />
            ) : (
              environments.map((environment) => {
                const status = String(environment.status).toUpperCase()
                // 可用性以统一 ready 标记为准：READY 但 ready=false 的过期条目必须显式显示不可用。
                const ready = environment.ready === true
                const displayStatus = status === 'READY' && !ready ? 'UNAVAILABLE' : status
                const toolNames = (environment.tools ?? []).map((tool) => tool.name).filter(Boolean)
                const skillNames = (environment.skills ?? []).map((skill) => skill.name).filter(Boolean)
                const lastSeen = formatLastSeen(environment.lastSeen, locale)
                return (
                  <article key={environment.name} className="info-card environment-card">
                    <div className="head">
                      <div className="head-content">
                        <div className="text-content">
                          <h3 title={environment.name}>{environment.name}</h3>
                          <p title={lastSeen || undefined}>
                            {lastSeen
                              ? `${t('ai.environment.lastSeen')} · ${lastSeen}`
                              : t('ai.environment.lastSeen')}
                          </p>
                        </div>
                        <span className={`status-pill${ready ? ' is-ready' : ' is-offline'}`}>{displayStatus}</span>
                      </div>
                    </div>
                    <div className="meta-block">
                      <TagRow label={t('ai.environment.tools')} names={toolNames} />
                      <TagRow label={t('ai.environment.skills')} names={skillNames} />
                      {(environment.mcpServers ?? []).map((server) => (
                        <McpServerRow key={server.name} server={server} t={t} />
                      ))}
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
