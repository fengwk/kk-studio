import { formatBackendDate } from '@/shared/lib/date-format'
import { useI18n } from '@/shared/i18n'
import { ResourceCardLayout, type ResourceCardRow } from '@/features/catalog/AiResourceCardLayout'
import type { McpServerSummaryDTO } from '@/shared/api/generated/contracts'

/** 列表摘要只携带安全元数据：URL 与 headers 从不进入卡片。 */
function summaryRows(server: McpServerSummaryDTO, t: ReturnType<typeof useI18n>['t']): ResourceCardRow[] {
  const updateTime = formatBackendDate(server.updatedAt)
  const statusLabel =
    server.discoveryStatus === 'AVAILABLE'
      ? t('ai.mcp.statusAvailable')
      : t('ai.mcp.statusUnverified')

  const rows: ResourceCardRow[] = [
    {
      pairs: [
        { label: t('ai.mcp.status'), value: statusLabel },
        { label: t('ai.mcp.toolCount'), value: String(server.toolCount) },
      ],
    },
    {
      pairs: [
        { label: t('ai.mcp.version'), value: String(server.version) },
        { label: t('ai.catalog.card.timeout'), value: `${server.timeoutMillis}ms` },
      ],
    },
    {
      pairs: [
        {
          label: t('ai.mcp.enabledState'),
          value: server.enabled ? t('ai.mcp.enabled') : t('ai.mcp.disabled'),
        },
      ],
    },
    ...(updateTime ? [[t('ai.mcp.updated'), updateTime] as [string, string]] : []),
  ]
  return rows
}

export function McpServerCard({
  server,
  deletePending,
  onEdit,
  onDelete,
}: {
  server: McpServerSummaryDTO
  deletePending: boolean
  onEdit: () => void
  onDelete: () => void
}) {
  const { t } = useI18n()
  return (
    <ResourceCardLayout
      icon="server"
      title={server.name}
      subtitle="Streamable HTTP"
      rows={summaryRows(server, t)}
      editAriaLabel={`${t('ai.mcp.edit')} ${server.name}`}
      deleteAriaLabel={`${t('ai.mcp.delete')} ${server.name}`}
      onEdit={onEdit}
      onDelete={onDelete}
      deletePending={deletePending}
    />
  )
}
