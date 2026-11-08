import { useCallback, useEffect, useMemo, useState } from 'react'
import { Badge } from '@/shared/ui/data/Badge'
import { useMutation, useQueries, useQuery, useQueryClient } from '@tanstack/react-query'
import { Box, Download, KeyRound, Pencil, SlidersHorizontal, Trash2 } from 'lucide-react'
import {
  environmentProxyText,
  filterEnvironments,
  formatTimestamp,
  isEnvironmentRuntimeUnavailableError,
  presentEnvironmentConfig,
  presentUpdateProgress,
} from '@/features/environment/environment-utils'
import { EnvironmentInstallModal } from '@/features/environment/EnvironmentInstallModal'
import { EnvironmentConfigModal } from '@/features/environment/EnvironmentConfigModal'
import { EnvironmentConfigStatusBadge } from '@/features/environment/EnvironmentConfigStatusBadge'
import { EnvironmentManagementModal } from '@/features/environment/EnvironmentManagementModal'
import { environmentRuntimeQueryOptions } from '@/features/environment/environment-runtime-query'
import { CreateCard } from '@/shared/ui/feedback/CreateCard'
import { StateBlock } from '@/shared/ui/feedback/StateBlock'
import { Button } from '@/shared/ui/controls/Button'
import { ResourceCardSkeleton } from '@/shared/ui/cards/ResourceCardSkeleton'
import { ResourceCard, type ResourceCardMetaRow } from '@/shared/ui/cards/ResourceCard'
import { ResourceGrid } from '@/shared/ui/cards/ResourceGrid'
import { ConfirmActionModal } from '@/shared/ui/overlays/ConfirmActionModal'
import { environmentService } from '@/features/environment/environment-service'
import { useResourceSync } from '@/shared/app-events/useResourceSync'
import { isConflictError } from '@/shared/api/client'
import { presentConflict, type ConflictPresentation } from '@/shared/conflict/conflict-presenter'
import { ConflictPresenter } from '@/shared/conflict/ConflictPresenter'
import { AiNavigation } from '@/app/ai-console/AiNavigation'
import { queryKeys } from '@/shared/lib/query-keys'
import { useI18n, type InterpolationValues, type TranslationKey } from '@/shared/i18n'
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
function connectionBadgeTone(ready: boolean, status: EnvironmentDTO['status']): 'success' | 'info' | 'warning' {
  if (ready && status === 'READY') return 'success'
  if (status === 'CONNECTING') return 'info'
  return 'warning'
}

export function EnvironmentsPage() {
  const { t } = useI18n()
  const queryClient = useQueryClient()

  const [editor, setEditor] = useState<EditorState | null>(null)
  const [installTarget, setInstallTarget] = useState<EnvironmentDTO | null>(null)
  const [uninstallTarget, setUninstallTarget] = useState<EnvironmentDTO | null>(null)

  const [deleteTarget, setDeleteTarget] = useState<EnvironmentDTO | null>(null)
  const [deleteError, setDeleteError] = useState<string | null>(null)
  const [updateTarget, setUpdateTarget] = useState<EnvironmentDTO | null>(null)
  const [updateError, setUpdateError] = useState<string | null>(null)
  const [rotateTarget, setRotateTarget] = useState<EnvironmentDTO | null>(null)
  const [rotateError, setRotateError] = useState<string | null>(null)
  const [manageTarget, setManageTarget] = useState<EnvironmentDTO | null>(null)
  const [conflict, setConflict] = useState<ConflictPresentation | null>(null)

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

  const environments = useMemo(
    () => filterEnvironments(environmentsQuery.data ?? [], ''),
    [environmentsQuery.data],
  )

  // 运行快照对 READY 与有效 CONNECTING 都可读；只有 OFFLINE 没有有效 owner 租约。
  const runtimeQueries = useQueries({
    queries: environments.map((environment) => environmentRuntimeQueryOptions(environment)),
  })

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

  const updateMutation = useMutation({
    mutationFn: (id: string) => environmentService.startEnvironmentUpdate(id),
    onSuccess: (result, id) => {
      // 命令回执携带当前快照；先写入缓存再回读列表，界面不会把发送前状态误标成已启动。
      queryClient.setQueryData(queryKeys.environments.runtime(id), result.runtimeStatus)
      setUpdateTarget(null)
      setUpdateError(null)
      void queryClient.invalidateQueries({ queryKey: queryKeys.environments.all })
    },
    onError: (err: unknown) => {
      if (isEnvironmentRuntimeUnavailableError(err)) {
        setUpdateError(t('ai.environment.runtime.unavailable'))
        return
      }
      if (isConflictError(err)) {
        setConflict(presentConflict(err))
        setUpdateTarget(null)
        setUpdateError(null)
        return
      }
      setUpdateError(err instanceof Error ? err.message : String(err))
    },
  })

  const rotateMutation = useMutation({
    mutationFn: async ({ id, expectedVersion }: { id: string; expectedVersion: string }) => {
      const card = await environmentService.rotateToken(id, expectedVersion)
      return { ...card, registrationToken: null }
    },
    onSuccess: () => {
      setRotateTarget(null)
      setRotateError(null)
      void queryClient.invalidateQueries({ queryKey: queryKeys.environments.all })
    },
    onError: (err: unknown) => {
      if (isConflictError(err)) {
        setConflict(presentConflict(err))
        setRotateTarget(null)
        setRotateError(null)
        return
      }
      setRotateError(err instanceof Error ? err.message : String(err))
    },
  })

  const deleteMutation = useMutation({
    mutationFn: ({ id, expectedVersion }: { id: string; expectedVersion: string }) =>
      environmentService.deleteEnvironment(id, expectedVersion),
    onSuccess: () => {
      setDeleteTarget(null)
      setDeleteError(null)
      void queryClient.invalidateQueries({ queryKey: queryKeys.environments.all })
    },
    onError: (err: unknown) => {
      if (isConflictError(err)) {
        setConflict(presentConflict(err))
        setDeleteTarget(null)
        setDeleteError(null)
        return
      }
      setDeleteError(err instanceof Error ? err.message : String(err))
    },
  })

  return (
    <section className="screen active">
      <nav className="subbar">
        <AiNavigation />
      </nav>
      <div className="screen-body">
        {environmentsQuery.isLoading && <ResourceCardSkeleton label={t('ai.environment.loading')} />}
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
        {!environmentsQuery.isLoading && (
          <ResourceGrid>
            <CreateCard
              title={t('ai.environment.create')}
              subtitle={t('ai.environment.createDescription')}
              onClick={() => setEditor({ mode: 'create' })}
            />
            {environments.map((environment, index) => {
              const status = environment.status
              const ready = environment.ready === true
              const displayStatus = status === 'READY' && !ready ? 'UNAVAILABLE' : status
              const capabilityIds = (environment.capabilities ?? [])
                .map((capability) => capability.id)
                .filter(Boolean)
              const lastSeen = formatTimestamp(environment.lastSeenAt)

              const runtimeQuery = runtimeQueries[index]
              const runtimeStatus = runtimeQuery.isError ? undefined : runtimeQuery.data?.status
              const configView = presentEnvironmentConfig(
                environment,
                runtimeQuery.isError ? undefined : runtimeQuery.data,
              )
              const runtimeUnavailable =
                status === 'OFFLINE' ||
                (runtimeQuery.isError &&
                  isEnvironmentRuntimeUnavailableError(runtimeQuery.error))
              // 当前版本只采用 Daemon 上报：live 快照优先，不可用时回退到最后一次 READY 上报值。
              const liveDaemonVersion = runtimeStatus?.daemonVersion?.trim()
              const lastReportedDaemonVersion = environment.daemonVersion?.trim()
              const daemonVersion =
                liveDaemonVersion ||
                (runtimeStatus == null ? lastReportedDaemonVersion : undefined) ||
                t('ai.environment.runtime.unreported')
              let runtimeText: string
              if (runtimeUnavailable) {
                runtimeText = t('ai.environment.runtime.unavailable')
              } else if (runtimeQuery.isError) {
                const errorText =
                  runtimeQuery.error instanceof Error
                    ? runtimeQuery.error.message.trim()
                    : ''
                runtimeText = errorText || t('ai.environment.runtime.loadFailed')
              } else if (runtimeStatus == null) {
                runtimeText = t('ai.environment.runtime.loading')
              } else if (runtimeStatus.update) {
                runtimeText = formatUpdateProgress(runtimeStatus.update, t)
              } else {
                runtimeText = t('ai.environment.update.none')
              }

              const rows: ResourceCardMetaRow[] = []
              rows.push({
                label: t('ai.environment.config.status'),
                value: (
                  <span className="environment-config-status">
                    <EnvironmentConfigStatusBadge view={configView} />
                    {configView.error ? (
                      <span className="field-help">
                        {configView.error.code}
                        {configView.error.field ? ` · ${configView.error.field}` : ''}
                      </span>
                    ) : null}
                  </span>
                ),
                wrap: true,
              })
              rows.push([t('ai.environment.config.cardVersion'), environment.version])
              rows.push([
                t('ai.environment.config.appliedVersion'),
                configView.appliedVersion ?? '—',
              ])
              rows.push([
                t('ai.environment.config.proxy'),
                environmentProxyText(configView, t('ai.environment.config.proxyDirect')),
              ])
              rows.push([t('ai.environment.daemonVersion'), daemonVersion])
              rows.push({
                label: t('ai.environment.updateStatus'),
                value: runtimeText,
                wrap: true,
              })
              if (environment.userName) {
                rows.push([t('ai.environment.userName'), environment.userName])
              }
              if (environment.note) {
                rows.push({ label: t('ai.environment.form.note'), value: environment.note, wrap: true })
              }
              // 能力清单始终占一行：为空时留空，结构不随数据有无而变。
              rows.push({ label: t('ai.environment.capabilities'), tags: capabilityIds })

              return (
                <ResourceCard
                  key={environment.id}
                  className="environment-card"
                  icon={<Box aria-hidden="true" />}
                  title={environment.name}
                  subtitle={environment.id}
                  badge={
                    <Badge tone={connectionBadgeTone(ready, status)}>{displayStatus}</Badge>
                  }
                  meta={rows}
                  actions={
                    <>
                      <Button
                        size="compact"
                        aria-label={`${t('ai.environment.install')} ${environment.name}`}
                        onClick={() => setInstallTarget(environment)}
                      >
                        <Download aria-hidden="true" />
                        {t('ai.environment.install')}
                      </Button>
                      <Button
                        variant="ghost"
                        size="compact"
                        aria-label={`${t('ai.environment.uninstall')} ${environment.name}`}
                        onClick={() => setUninstallTarget(environment)}
                      >
                        {t('ai.environment.uninstall')}
                      </Button>
                      <Button
                        variant="ghost"
                        size="compact"
                        aria-label={`${t('ai.environment.form.edit')} ${environment.name}`}
                        onClick={() => setEditor({ mode: 'edit', environment })}
                      >
                        <Pencil aria-hidden="true" />
                        {t('ai.environment.form.edit')}
                      </Button>
                      <Button
                        variant="ghost"
                        size="compact"
                        aria-label={`${t('ai.environment.manage')} ${environment.name}`}
                        onClick={() => {
                          setConflict(null)
                          setManageTarget(environment)
                        }}
                      >
                        <SlidersHorizontal aria-hidden="true" />
                        {t('ai.environment.manage')}
                      </Button>
                      <Button
                        variant="ghost"
                        size="compact"
                        aria-label={`${t('ai.environment.update')} ${environment.name}`}
                        onClick={() => {
                          setConflict(null)
                          setUpdateError(null)
                          setUpdateTarget(environment)
                        }}
                      >
                        {t('ai.environment.update')}
                      </Button>
                      <Button
                        variant="ghost"
                        size="compact"
                        aria-label={`${t('ai.environment.rotateToken')} ${environment.name}`}
                        onClick={() => {
                          setConflict(null)
                          setRotateError(null)
                          setRotateTarget(environment)
                        }}
                      >
                        <KeyRound aria-hidden="true" />
                        {t('ai.environment.rotateToken')}
                      </Button>
                      <Button
                        variant="ghost"
                        size="compact"
                        danger
                        aria-label={`${t('ai.environment.delete')} ${environment.name}`}
                        onClick={() => {
                          setConflict(null)
                          setDeleteError(null)
                          setDeleteTarget(environment)
                        }}
                        disabled={deleteMutation.isPending && deleteTarget?.id === environment.id}
                      >
                        <Trash2 aria-hidden="true" />
                        {t('ai.catalog.action.delete')}
                      </Button>
                    </>
                  }
                >
                  {lastSeen ? (
                    <p className="inline-hint" title={lastSeen}>
                      {`${t('ai.environment.lastSeen')} · ${lastSeen}`}
                    </p>
                  ) : null}
                </ResourceCard>
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

      {manageTarget && (
        <EnvironmentManagementModal
          environment={manageTarget}
          onClose={() => setManageTarget(null)}
        />
      )}

      <ConflictPresenter
        className="modal-touch-target"
        conflict={conflict}
        onRefresh={() => {
          setConflict(null)
          setEditor(null)
          setUpdateTarget(null)
          setUpdateError(null)
          setRotateTarget(null)
          setRotateError(null)
          setDeleteTarget(null)
          setDeleteError(null)
          setManageTarget(null)
          void queryClient.invalidateQueries({ queryKey: queryKeys.environments.all })
        }}
        onClose={() => setConflict(null)}
      />
    </section>
  )
}
