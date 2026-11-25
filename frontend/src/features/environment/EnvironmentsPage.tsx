import { useCallback, useDeferredValue, useEffect, useMemo, useState } from 'react'
import { Badge } from '@/shared/ui/data/Badge'
import { useQueries, useQuery, useQueryClient } from '@tanstack/react-query'
import { Box, CircleAlert, Download, SlidersHorizontal } from 'lucide-react'
import {
  ENVIRONMENT_STATUS_LABEL_KEY,
  environmentDisplayStatus,
  isEnvironmentRuntimeUnavailableError,
  filterEnvironments,
  presentEnvironmentConfig,
  presentUpdateProgress,
  type EnvironmentDisplayStatus,
} from '@/features/environment/environment-utils'
import { EnvironmentInstallModal } from '@/features/environment/EnvironmentInstallModal'
import { EnvironmentConfigModal } from '@/features/environment/EnvironmentConfigModal'
import { EnvironmentConfigStatusBadge } from '@/features/environment/EnvironmentConfigStatusBadge'
import { EnvironmentManagementModal } from '@/features/environment/EnvironmentManagementModal'
import { environmentRuntimeQueryOptions } from '@/features/environment/environment-runtime-query'
import { environmentService } from '@/features/environment/environment-service'
import { CreateCard } from '@/shared/ui/feedback/CreateCard'
import { StateBlock } from '@/shared/ui/feedback/StateBlock'
import { SearchField } from '@/shared/ui/controls/SearchField'
import { Button } from '@/shared/ui/controls/Button'
import { ResourceCardSkeleton } from '@/shared/ui/cards/ResourceCardSkeleton'
import { ResourceCard, type ResourceCardMetaRow } from '@/shared/ui/cards/ResourceCard'
import { ResourceGrid } from '@/shared/ui/cards/ResourceGrid'
import { ConfirmActionModal } from '@/shared/ui/overlays/ConfirmActionModal'
import { useResourceSync } from '@/shared/app-events/useResourceSync'
import { ConflictPresenter } from '@/shared/conflict/ConflictPresenter'
import { AiNavigation } from '@/app/ai-console/AiNavigation'
import { queryKeys } from '@/shared/lib/query-keys'
import { useI18n, type InterpolationValues, type TranslationKey } from '@/shared/i18n'
import { useEnvironmentCardActions } from '@/features/environment/useEnvironmentCardActions'
import './environment-page.css'
import type {
  EnvironmentDTO,
  EnvironmentUpdateProgressDTO,
} from '@/shared/api/generated/contracts'

type Translate = (key: TranslationKey, values?: InterpolationValues) => string

type EditorState =
  | { mode: 'create' }
  | { mode: 'edit'; environment: EnvironmentDTO }

function formatUpdateProgress(update: EnvironmentUpdateProgressDTO, t: Translate): string {
  const progress = presentUpdateProgress(update)
  if (progress.percent == null || progress.total == null) {
    return t('ai.environment.update.progressUnknownTotal', {
      targetVersion: update.targetVersion,
      downloaded: progress.downloaded,
    })
  }
  return t('ai.environment.update.progressKnownTotal', {
    targetVersion: update.targetVersion,
    downloaded: progress.downloaded,
    total: progress.total,
    percent: progress.percent,
  })
}

/**
 * 状态截止点回读的容差：越过截止点一个很小的余量再回读，避免与服务端时钟在边界上竞争。
 *
 * 它是「一次回读」的容差而非轮询周期：每个截止点只排一次，截止点变化才会重排。
 */
const STATUS_EXPIRY_RECHECK_SLACK_MS = 250

/** 权威列表里最早的状态截止时间（毫秒）；没有任何截止时间时为 null。 */
function earliestStatusExpiresAt(cards: EnvironmentDTO[]): number | null {
  let earliest: number | null = null
  for (const card of cards) {
    const at = card.statusExpiresAt
    if (at == null) {
      continue
    }
    if (earliest == null || at < earliest) {
      earliest = at
    }
  }
  return earliest
}

/** 连接状态与配置状态是两个事实：READY 且租约有效才展示为 READY，过期租约明确降级。 */
function connectionBadgeTone(
  status: EnvironmentDisplayStatus,
): 'success' | 'info' | 'warning' {
  if (status === 'READY') return 'success'
  if (status === 'CONNECTING') return 'info'
  return 'warning'
}

export function EnvironmentsPage() {
  const { t } = useI18n()
  const queryClient = useQueryClient()

  const [editor, setEditor] = useState<EditorState | null>(null)
  const [installTarget, setInstallTarget] = useState<EnvironmentDTO | null>(null)
  const [uninstallTarget, setUninstallTarget] = useState<EnvironmentDTO | null>(null)

  const [manageId, setManageId] = useState<string | null>(null)
  const [search, setSearch] = useState('')
  // 输入保持即时响应，过滤按延迟值进行，避免每个按键都重排卡片。
  const deferredSearch = useDeferredValue(search)

  const {
    deleteTarget,
    setDeleteTarget,
    deleteError,
    setDeleteError,
    updateTarget,
    setUpdateTarget,
    updateError,
    setUpdateError,
    rotateTarget,
    setRotateTarget,
    rotateError,
    setRotateError,
    conflict,
    setConflict,
    clearAllTargets,
    updateMutation,
    updateReceipts,
    rotateMutation,
    deleteMutation,
  } = useEnvironmentCardActions({ t })

  const invalidateEnvironments = useCallback(() => {
    void queryClient.invalidateQueries({ queryKey: queryKeys.environments.all })
  }, [queryClient])

  const environmentsQuery = useQuery({
    queryKey: queryKeys.environments.list,
    queryFn: () => environmentService.listEnvironments(),
  })

  // 环境事实（注册表与连接租约，含心跳续租、断线与注销）由服务端在提交后推送 changed：
  // 只提示回读权威列表，因此不再用固定轮询掩盖状态变化；订阅建立（首订与重连重订阅）
  // 与 resync 同样回读，关闭断线窗口。
  useResourceSync({ kind: 'environments' }, {
    sync: invalidateEnvironments,
    syncOn: (name) => name === 'changed',
  })

  // 完整列表是运行查询、状态回读、管理弹窗与刷新反馈的唯一事实来源。
  const environments = environmentsQuery.data ?? []

  // 运行快照对 READY 与有效 CONNECTING 都可读；只有 OFFLINE 没有有效 owner 租约。
  const runtimeQueries = useQueries({
    queries: environments.map((environment) => environmentRuntimeQueryOptions(environment)),
  })

  // 搜索与排序只改变卡片；运行快照仍按完整列表的 id 配对。
  const runtimeById = new Map(environments.map(({ id }, index) => [id, runtimeQueries[index]]))
  const cards = filterEnvironments(environments, deferredSearch)

  // 连接失效是时间事实，不是写入事实：服务端已给出每个有效状态的截止时间，这里只按最早的截止点排一次回读，
  // 不轮询、也不维护客户端过期集合。心跳续租或任何 changed 推送更新权威数据后，这个定时器会被取消并按新截止点重排；
  // 回读得到 OFFLINE（statusExpiresAt 为 null）后自然停止。
  const statusExpiresAt = useMemo(
    () => earliestStatusExpiresAt(environmentsQuery.data ?? []),
    [environmentsQuery.data],
  )

  useEffect(() => {
    if (statusExpiresAt == null) {
      return
    }
    // 截止点只是状态成立的上界：稍微越过它再回读，避免与服务端时钟在边界上竞争。
    const delay = Math.max(statusExpiresAt - Date.now(), 0) + STATUS_EXPIRY_RECHECK_SLACK_MS
    const timer = setTimeout(invalidateEnvironments, delay)
    return () => clearTimeout(timer)
  }, [statusExpiresAt, invalidateEnvironments])

  const managedEnvironment = environments.find(({ id }) => id === manageId)
  const retryList = () => void environmentsQuery.refetch({ cancelRefetch: false })
  const retryButton = (
    <Button size="compact" variant="secondary" disabled={environmentsQuery.isFetching} onClick={retryList}>
      {t('shared.retry')}
    </Button>
  )

  return (
    <section className="screen active">
      <div className="subbar environment-toolbar">
        <AiNavigation />
        <SearchField
          value={search}
          onChange={setSearch}
          placeholder={t('ai.environment.searchPlaceholder')}
          aria-label={t('ai.environment.searchLabel')}
        />
        <div className="environment-refresh-slot">
          {environmentsQuery.isRefetchError && (
            <>
              <div className="environment-feedback environment-refresh-wide">
                <CircleAlert aria-hidden="true" />
                {t('ai.environment.refreshStale')}
                {retryButton}
              </div>
              <details className="environment-refresh-narrow">
                <summary className="environment-feedback">
                  <CircleAlert aria-hidden="true" />
                  {t('ai.environment.refreshFailed')}
                </summary>
                <div className="environment-feedback environment-refresh-details">
                  {t('ai.environment.refreshStale')}
                  {retryButton}
                </div>
              </details>
            </>
          )}
        </div>
      </div>
      <div className="screen-body">
        {environmentsQuery.data == null && (environmentsQuery.isLoading ? (
          <ResourceCardSkeleton variant="environment" label={t('ai.environment.loading')} />
        ) : environmentsQuery.isError && (
          <div className="environment-feedback">
            <CircleAlert aria-hidden="true" />
            <span role="status">{t('ai.environment.loadFailed')}</span>
            {retryButton}
          </div>
        ))}
        {environmentsQuery.data != null && deferredSearch.trim() !== '' && cards.length === 0 && (
          <StateBlock title={t('ai.environment.empty.noMatch')} />
        )}
        {environmentsQuery.data != null && (
          <ResourceGrid>
            <CreateCard
              title={t('ai.environment.create')}
              subtitle={t('ai.environment.createDescription')}
              onClick={() => setEditor({ mode: 'create' })}
            />
            {cards.map((environment) => {
              const runtimeQuery = runtimeById.get(environment.id)!
              const displayStatus = environmentDisplayStatus(environment)
              const status = environment.status
              const runtimeUnavailable = status === 'OFFLINE' ||
                isEnvironmentRuntimeUnavailableError(runtimeQuery.error)
              const runtime = runtimeUnavailable || runtimeQuery.isError ? undefined : runtimeQuery.data
              const runtimeStatus = runtime?.status
              const configView = presentEnvironmentConfig(environment, runtime)
              const liveVersion = runtimeStatus?.daemonVersion?.trim()
              const lastVersion = runtimeQuery.data?.status.daemonVersion?.trim() || environment.daemonVersion?.trim()
              const daemonVersion = liveVersion || lastVersion || t('ai.environment.runtime.unreported')
              const receipt = updateReceipts[environment.id]
              const matchesReceipt = receipt != null && receipt.revision === runtime?.revision &&
                receipt.daemonVersion === runtimeStatus?.daemonVersion
              let runtimeText: string
              if (runtimeUnavailable) {
                runtimeText = t('ai.environment.runtime.unavailable')
              } else if (runtimeQuery.isError) {
                runtimeText = t('ai.environment.runtime.loadFailed')
              } else if (runtimeStatus == null) {
                runtimeText = t('ai.environment.runtime.loading')
              } else if (runtimeStatus.update) {
                runtimeText = formatUpdateProgress(runtimeStatus.update, t)
              } else if (matchesReceipt) {
                // 回执只描述命令返回时的状态，不覆盖后续运行快照。
                runtimeText = t(receipt.commandStatus === 'CURRENT'
                  ? 'ai.environment.update.current'
                  : 'ai.environment.update.sentUnconfirmed')
              } else {
                runtimeText = t('ai.environment.update.none')
              }

              const showConfigIssue = runtimeStatus && !runtimeStatus.update && !matchesReceipt &&
                (configView.state === 'failed' || configView.state === 'pendingApply')
              const rows: ResourceCardMetaRow[] = [
                // 同一信息行在兄弟卡片上共用同一标签：版本来源（实时或上次上报）不改写行名。
                [t('ai.environment.daemonVersion'), daemonVersion],
                {
                  label: t(showConfigIssue ? 'ai.environment.config.status' : 'ai.environment.updateStatus'),
                  value: showConfigIssue ? <EnvironmentConfigStatusBadge view={configView} />
                    : runtimeQuery.isError && !runtimeUnavailable ? (
                      <span className="environment-feedback">
                        <CircleAlert aria-hidden="true" />
                        {runtimeText}
                      </span>
                    ) : runtimeText,
                  wrap: true,
                },
              ]

              return (
                <ResourceCard
                  key={environment.id}
                  className="environment-card"
                  icon={<Box aria-hidden="true" />}
                  title={environment.name}
                  subtitle={environment.note || undefined}
                  badge={
                    <Badge tone={connectionBadgeTone(displayStatus)} title={displayStatus}>
                      {t(ENVIRONMENT_STATUS_LABEL_KEY[displayStatus])}
                    </Badge>
                  }
                  meta={rows}
                  actions={
                    <>
                      <Button
                        size="compact"
                        aria-label={`${t('ai.environment.installOverwrite')} ${environment.name}`}
                        onClick={() => setInstallTarget(environment)}
                      >
                        <Download aria-hidden="true" />
                        {t('ai.environment.installOverwrite')}
                      </Button>
                      <Button
                        variant="secondary"
                        size="compact"
                        aria-label={`${t('ai.environment.manage')} ${environment.name}`}
                        onClick={() => {
                          setConflict(null)
                          setManageId(environment.id)
                        }}
                      >
                        <SlidersHorizontal aria-hidden="true" />
                        {t('ai.environment.manage')}
                      </Button>
                      <Button variant="secondary" size="compact" aria-label={`${t('ai.environment.form.edit')} ${environment.name}`}
                        onClick={() => setEditor({ mode: 'edit', environment })}>
                        {t('ai.environment.form.edit')}
                      </Button>
                      <Button variant="secondary" size="compact" danger aria-label={`${t('ai.environment.delete')} ${environment.name}`}
                        onClick={() => { setDeleteError(null); setDeleteTarget(environment) }}>
                        {t('ai.environment.delete')}
                      </Button>
                    </>
                  }
                />
              )
            })}
          </ResourceGrid>
        )}
      </div>

      {editor && (
        <EnvironmentConfigModal
          environment={editor.mode === 'edit' ? editor.environment : undefined}
          onClose={() => setEditor(null)}
          onCreated={(card) => {
            setEditor(null)
            setInstallTarget(card)
          }}
        />
      )}

      {updateTarget && (
        <ConfirmActionModal
          className="modal-touch-target"
          modal={{
            title: t('ai.environment.update'),
            description: t('ai.environment.updateConfirm', { name: updateTarget.name }),
            confirmLabel: t('ai.environment.update'),
            icon: 'refresh',
            error: updateError,
            onConfirm: () => updateMutation.mutate(updateTarget.id),
          }}
          pending={updateMutation.isPending}
          onClose={() => {
            setUpdateTarget(null)
            setUpdateError(null)
          }}
        />
      )}

      {rotateTarget && (
        <ConfirmActionModal
          className="modal-touch-target"
          modal={{
            title: t('ai.environment.rotateToken'),
            description: t('ai.environment.rotateTokenConfirm', { name: rotateTarget.name }),
            confirmLabel: t('ai.environment.rotateToken'),
            icon: 'refresh',
            error: rotateError,
            onConfirm: () =>
              rotateMutation.mutate({
                id: rotateTarget.id,
                expectedVersion: rotateTarget.version,
              }),
          }}
          pending={rotateMutation.isPending}
          onClose={() => {
            setRotateTarget(null)
            setRotateError(null)
          }}
        />
      )}

      {deleteTarget && (
        <ConfirmActionModal
          className="modal-touch-target"
          modal={{
            title: t('ai.environment.delete'),
            description: t('ai.environment.deleteConfirm', { name: deleteTarget.name }),
            confirmLabel: t('ai.environment.delete'),
            icon: 'delete',
            tone: 'danger',
            error: deleteError,
            onConfirm: () =>
              deleteMutation.mutate({
                id: deleteTarget.id,
                expectedVersion: deleteTarget.version,
              }),
          }}
          pending={deleteMutation.isPending}
          onClose={() => {
            setDeleteTarget(null)
            setDeleteError(null)
          }}
        />
      )}

      {installTarget && (
        <EnvironmentInstallModal
          environment={installTarget}
          onClose={() => setInstallTarget(null)}
        />
      )}
      {uninstallTarget && (
        <EnvironmentInstallModal
          environment={uninstallTarget}
          uninstall
          onClose={() => setUninstallTarget(null)}
        />
      )}

      {managedEnvironment && (
        <EnvironmentManagementModal
          environment={managedEnvironment}
          onClose={() => setManageId(null)}
          actions={[
            { label: t('ai.environment.update'), run: () => {
              setUpdateError(null)
              setUpdateTarget(managedEnvironment)
            } },
            { label: t('ai.environment.uninstall'), run: () => setUninstallTarget(managedEnvironment) },
            { label: t('ai.environment.rotateToken'), run: () => {
              setRotateError(null)
              setRotateTarget(managedEnvironment)
            } },
          ]}
        />
      )}

      <ConflictPresenter
        className="modal-touch-target"
        conflict={conflict}
        onRefresh={() => {
          clearAllTargets()
          setEditor(null)
          setManageId(null)
          void queryClient.invalidateQueries({ queryKey: queryKeys.environments.all })
        }}
        onClose={() => setConflict(null)}
      />
    </section>
  )
}
