/**
 * 全局底部终端面板。
 *
 * - 只在控制器 `visible` 时打开权威环境 query 与 environments 订阅，不批量 OPEN；
 * - tabs 来自权威环境列表：仅 READY 可新选，已有会话的离线环境可只读查看；
 * - 控制资格 = 环境 READY + 连接 open + 查询未失败；只能把会话的 hasControl 调低，
 *   环境离线、连接断开或回读失败时保留已有末屏为只读，绝不销毁 viewport 丢屏幕/焦点；
 * - viewport 以 environmentId 为 key：切换环境必须重新挂载，避免复用旧环境网格；
 * - 终止确认冻结完整目标（environment/daemon/terminal/writer），点击时按控制器当前快照复验。
 */

import { useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { environmentService } from '@/shared/api/environment-service'
import type { EnvironmentCardDTO } from '@/shared/api/contracts/ai-environment'
import type { InstantTimestamp } from '@/shared/api/contracts/base'
import { useApplicationEvents } from '@/shared/app-events'
import { useI18n } from '@/shared/i18n'
import { queryKeys } from '@/shared/lib/query-keys'
import { useReadModelFreshnessRecheck } from '@/shared/lib/useReadModelFreshnessRecheck'
import { Button } from '@/shared/ui/controls/Button'
import { Tabs } from '@/shared/ui/controls/Tabs'
import { StateBlock } from '@/shared/ui/feedback/StateBlock'
import { ConfirmActionModal } from '@/shared/ui/overlays/ConfirmActionModal'
import type { ConfirmModalState } from '@/shared/ui/overlays/confirm-modal'
import { useTerminal } from './terminal-context'
import type { TerminalNotice, TerminalSessionSnapshot } from './terminal-controller'
import type { TerminalStatus } from './terminal-control-codec'
import { TerminalViewport } from './TerminalViewport'
import './terminal.css'

const STATUS_KEY: Record<TerminalStatus, string> = {
  RUNNING: 'shell.status.running',
  EXITED: 'shell.status.exited',
  FAILED: 'shell.status.failed',
}

const NOTICE_KEY: Record<TerminalNotice, string> = {
  unavailable: 'shell.notice.unavailable',
  backpressure: 'shell.notice.backpressure',
  'not-written': 'shell.notice.not-written',
  'outcome-unknown': 'shell.notice.outcome-unknown',
  'control-rejected': 'shell.notice.control-rejected',
  'stale-mode': 'shell.notice.stale-mode',
  failed: 'shell.notice.failed',
  'scope-limit': 'shell.notice.scope-limit',
}

/** 权威列表里最早的、可解析的状态截止时间（保留服务端原始表示供回读 hook 使用）。 */
function earliestStatusExpiresAt(cards: EnvironmentCardDTO[]): InstantTimestamp {
  let best: { at: number; raw: InstantTimestamp } | null = null
  for (const card of cards) {
    const raw = card.statusExpiresAt
    if (raw == null) {
      continue
    }
    const at = typeof raw === 'number' ? raw * 1000 : Date.parse(raw)
    if (!Number.isFinite(at)) {
      continue
    }
    if (best === null || at < best.at) {
      best = { at, raw }
    }
  }
  return best?.raw ?? null
}

export function TerminalPanel() {
  const { t } = useI18n()
  const { controller, snapshot, hide } = useTerminal()
  const queryClient = useQueryClient()
  const applicationEvents = useApplicationEvents()
  const visible = snapshot.visible
  const [terminateModal, setTerminateModal] = useState<ConfirmModalState | null>(null)
  const [dialogNotice, setDialogNotice] = useState(false)
  const environmentsRef = useRef<EnvironmentCardDTO[]>([])
  // 冻结确认目标：弹窗期间换 tab/新 terminal/新 writer 不得终止新目标。
  const frozenTargetRef = useRef<{
    environmentId: string
    daemonInstanceId: string
    terminalId: string
    writerEpoch: string | null
  } | null>(null)

  const environmentsQuery = useQuery({
    queryKey: queryKeys.environments.list,
    queryFn: () => environmentService.listEnvironments(),
    enabled: visible,
  })
  const environments = useMemo(() => environmentsQuery.data ?? [], [environmentsQuery.data])
  const freshnessAt = useMemo(() => earliestStatusExpiresAt(environments), [environments])
  useReadModelFreshnessRecheck(queryKeys.environments.list, visible ? freshnessAt : null)

  useLayoutEffect(() => {
    environmentsRef.current = environments
  })

  const dismissTerminate = useCallback(() => {
    frozenTargetRef.current = null
    setTerminateModal(null)
    setDialogNotice(false)
  }, [])

  const invalidateEnvironments = useCallback(() => {
    void queryClient.invalidateQueries({ queryKey: queryKeys.environments.all })
  }, [queryClient])

  // 环境事实由服务端 changed 推送提示回读；关闭时（visible=false）不订阅。
  useEffect(() => {
    if (!visible) {
      return
    }
    return applicationEvents.subscribe(
      { kind: 'environments' },
      {
        onSubscribed: invalidateEnvironments,
        onEvent: (name) => {
          if (name === 'changed') {
            invalidateEnvironments()
          }
        },
        onResync: invalidateEnvironments,
      },
    )
  }, [visible, applicationEvents, invalidateEnvironments])

  if (!visible) {
    return null
  }

  const activeEnvironmentId = snapshot.activeEnvironmentId
  const session: TerminalSessionSnapshot | null = activeEnvironmentId === null
    ? null
    : snapshot.sessions.get(activeEnvironmentId) ?? null
  const activeEnvironment = environments.find((card) => card.id === activeEnvironmentId) ?? null
  const pending = session?.pending ?? false
  // 控制资格：环境 READY + 连接 open + 环境回读未失败。环境离线、连接断开或回读失败时，
  // 只把会话的 hasControl 调低为只读，绝不销毁 viewport（保留末屏与焦点）。
  const environmentReady = activeEnvironment?.ready ?? false
  const controlAvailable = environmentReady && snapshot.connectionStatus === 'open' && !environmentsQuery.isError
  const controlledSession = session !== null && session.hasControl && controlAvailable
  const hasCachedView = session?.view != null
  // 传给 viewport 的会话只能降低控制权：资格不足时一律只读。
  const viewportSession: TerminalSessionSnapshot | null = session !== null && session.hasControl && !controlAvailable
    ? { ...session, hasControl: false }
    : session

  const tabs = environments.map((card) => ({
    id: card.id,
    label: card.name,
    // 非 READY 环境不能新选；已有会话（末屏）仍可只读查看。
    disabled: !card.ready && !snapshot.sessions.has(card.id),
  }))

  const metaParts = [
    session?.executable ?? activeEnvironment?.name ?? '',
    session?.status != null ? t(STATUS_KEY[session.status]) : '',
    session != null ? (controlledSession ? t('shell.control.active') : t('shell.control.observing')) : '',
  ].filter((part) => part.length > 0)

  const body = (() => {
    // 已有末屏：无论环境/连接/查询状态如何都继续呈现（只读），不丢屏幕与焦点。
    if (session !== null && hasCachedView && viewportSession !== null) {
      return (
        <TerminalViewport
          key={session.environmentId}
          session={viewportSession}
          controller={controller}
        />
      )
    }
    // 没有末屏时才用状态块：加载/失败给出固定本地文案，绝不回显 Error.message。
    if (environmentsQuery.isLoading) {
      return <StateBlock title={t('shell.loading')} />
    }
    if (environmentsQuery.isError) {
      return <StateBlock title={t('shell.loadFailed')} tone="danger" />
    }
    if (environments.length === 0) {
      return <div className="terminal-panel__empty">{t('shell.empty.noEnvironments')}</div>
    }
    if (activeEnvironmentId === null || session === null) {
      return <div className="terminal-panel__empty">{t('shell.empty.selectEnvironment')}</div>
    }
    return <div className="terminal-panel__empty">{t('shell.empty.offline')}</div>
  })()

  const openTerminate = () => {
    if (session === null || session.identity === null) {
      return
    }
    frozenTargetRef.current = {
      environmentId: session.environmentId,
      daemonInstanceId: session.identity.daemonInstanceId,
      terminalId: session.identity.terminalId,
      writerEpoch: session.writer?.writerEpoch ?? null,
    }
    setDialogNotice(false)
    setTerminateModal({
      title: t('shell.dialog.terminateTitle'),
      description: t('shell.dialog.terminateDescription'),
      confirmLabel: t('shell.action.terminateConfirm'),
      tone: 'danger',
      onConfirm: () => {
        const target = frozenTargetRef.current
        // 点击瞬间以控制器当前快照为准（同一任务内 props 可能尚未重渲染）。
        const currentSnapshot = controller.getSnapshot()
        const current = target === null
          ? null
          : currentSnapshot.sessions.get(target.environmentId) ?? null
        const identity = current?.identity ?? null
        const environmentReadyNow =
          environmentsRef.current.find((card) => card.id === target?.environmentId)?.ready ?? false
        // 必须同时匹配活动环境、可见性、连接、环境 READY、会话仍在运行，以及完整目标身份。
        const sameTarget = target !== null
          && currentSnapshot.activeEnvironmentId === target.environmentId
          && currentSnapshot.visible
          && currentSnapshot.connectionStatus === 'open'
          && environmentReadyNow
          && current !== null
          && current.status === 'RUNNING'
          && identity !== null
          && identity.daemonInstanceId === target.daemonInstanceId
          && identity.terminalId === target.terminalId
          && (current.writer?.writerEpoch ?? null) === target.writerEpoch
        frozenTargetRef.current = null
        setTerminateModal(null)
        if (!sameTarget) {
          // 目标已变（含活动环境切换）：取消并提示用户在原目标上重新确认，绝不误伤。
          setDialogNotice(true)
          return
        }
        controller.terminate()
      },
    })
  }

  const closePanel = () => {
    dismissTerminate()
    hide()
  }

  return (
    <section className="terminal-panel" data-testid="terminal-panel" aria-label={t('shell.title')}>
      <header className="terminal-panel__header">
        <div className="terminal-panel__title">
          <span>{t('shell.title')}</span>
          <span className="terminal-panel__meta">{metaParts.join(' · ')}</span>
        </div>
        <div className="terminal-panel__actions">
          {session != null && controlAvailable && !session.hasControl && session.identity != null && session.viewApplied && session.status === 'RUNNING' && (
            <Button size="compact" disabled={pending} onClick={() => controller.claim()}>
              {t('shell.control.claim')}
            </Button>
          )}
          {session != null && controlAvailable && !session.hasControl && session.identity != null && session.viewApplied && session.status === 'RUNNING' && (
            <Button size="compact" variant="ghost" disabled={pending} onClick={() => controller.takeover()}>
              {t('shell.control.takeover')}
            </Button>
          )}
          {session != null && controlAvailable && session.hasControl && (
            <Button size="compact" variant="ghost" disabled={pending} onClick={() => controller.release()}>
              {t('shell.control.release')}
            </Button>
          )}
          {session != null && (
            <Button size="compact" variant="ghost" disabled={pending} onClick={() => controller.refresh()}>
              {t('shell.action.refresh')}
            </Button>
          )}
          {session != null && controlAvailable && (session.status === 'EXITED' || session.status === 'FAILED') && (
            <Button size="compact" disabled={pending} onClick={() => controller.restart()}>
              {t('shell.action.restart')}
            </Button>
          )}
          {session != null && controlAvailable && session.status === 'RUNNING' && session.identity != null && (
            <Button
              size="compact"
              variant="ghost"
              danger
              disabled={pending}
              onClick={openTerminate}
            >
              {t('shell.action.terminate')}
            </Button>
          )}
          <Button size="compact" variant="ghost" onClick={closePanel}>
            {t('shell.action.hide')}
          </Button>
        </div>
      </header>
      {session?.notice != null && (
        <div className="terminal-panel__notice" role="status">
          {t(NOTICE_KEY[session.notice])}
        </div>
      )}
      {dialogNotice && (
        <div className="terminal-panel__notice" role="status">
          {t('shell.dialog.targetChanged')}
        </div>
      )}
      {hasCachedView && (environmentsQuery.isLoading || environmentsQuery.isError) && (
        <div className="terminal-panel__notice" role="status">
          {environmentsQuery.isError ? t('shell.loadFailed') : t('shell.loading')}
        </div>
      )}
      <Tabs
        className="terminal-panel__tabs"
        panelClassName="terminal-panel__tabpanel"
        ariaLabel={t('shell.tabs.ariaLabel')}
        tabs={tabs}
        activeId={activeEnvironmentId ?? ''}
        onChange={(id) => controller.selectEnvironment(id)}
      >
        {body}
      </Tabs>
      <ConfirmActionModal
        modal={terminateModal}
        pending={pending}
        onClose={dismissTerminate}
      />
    </section>
  )
}
