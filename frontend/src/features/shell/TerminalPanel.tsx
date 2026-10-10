/**
 * 全局底部终端面板。
 *
 * - 只在控制器 `visible` 时打开权威环境 query 与 environments 订阅，不批量 OPEN；
 * - tabs 来自权威环境列表：仅 READY 可新选，已有会话的离线环境可只读查看；
 * - 统一使用共享 Button/Tabs/ConfirmActionModal；标题展示真实 executable/status/notice/控制态；
 * - pending 时禁用并发动作按钮；单个活动 viewport，不嵌入任何 pane、不注册假 shell 页面。
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
import type { TerminalNotice, TerminalSessionSnapshot, TerminalWorkspaceSnapshot } from './terminal-controller'
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
  const snapshotRef = useRef<TerminalWorkspaceSnapshot>(snapshot)
  useLayoutEffect(() => {
    snapshotRef.current = snapshot
  })
  // 冻结确认目标：弹窗期间换 tab/新 terminal/新 writer 不得终止新目标。
  const frozenTargetRef = useRef<{ environmentId: string; terminalId: string; writerEpoch: string | null } | null>(null)

  const environmentsQuery = useQuery({
    queryKey: queryKeys.environments.list,
    queryFn: () => environmentService.listEnvironments(),
    enabled: visible,
  })
  const environments = useMemo(() => environmentsQuery.data ?? [], [environmentsQuery.data])
  const freshnessAt = useMemo(() => earliestStatusExpiresAt(environments), [environments])
  useReadModelFreshnessRecheck(queryKeys.environments.list, visible ? freshnessAt : null)

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
  // 只有 READY 环境允许控制类动作；离线环境仅可查看已有末屏。
  const environmentReady = activeEnvironment?.ready ?? false

  const tabs = environments.map((card) => ({
    id: card.id,
    label: card.name,
    // 非 READY 环境不能新选；已有会话（末屏）仍可只读查看。
    disabled: !card.ready && !snapshot.sessions.has(card.id),
  }))

  const metaParts = [
    session?.executable ?? activeEnvironment?.name ?? '',
    session?.status != null ? t(STATUS_KEY[session.status]) : '',
    session != null ? (session.hasControl ? t('shell.control.active') : t('shell.control.observing')) : '',
  ].filter((part) => part.length > 0)

  const body = (() => {
    // 加载/错误不是“没有环境”：分别给出真实结果，避免把失败伪装成空态。
    if (environmentsQuery.isLoading) {
      return <StateBlock title={t('shell.loading')} />
    }
    if (environmentsQuery.isError) {
      return (
        <StateBlock
          title={
            environmentsQuery.error instanceof Error
              ? environmentsQuery.error.message
              : t('shell.loadFailed')
          }
          tone="danger"
        />
      )
    }
    if (environments.length === 0) {
      return <div className="terminal-panel__empty">{t('shell.empty.noEnvironments')}</div>
    }
    if (activeEnvironmentId === null || session === null) {
      return <div className="terminal-panel__empty">{t('shell.empty.selectEnvironment')}</div>
    }
    if (session.view === null && !environmentReady) {
      return <div className="terminal-panel__empty">{t('shell.empty.offline')}</div>
    }
    return <TerminalViewport session={session} controller={controller} />
  })()

  const openTerminate = () => {
    if (session === null || session.identity === null) {
      return
    }
    frozenTargetRef.current = {
      environmentId: session.environmentId,
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
        const current = target === null
          ? null
          : snapshotRef.current.sessions.get(target.environmentId) ?? null
        const identity = current?.identity ?? null
        const sameTarget = target !== null
          && identity !== null
          && identity.terminalId === target.terminalId
          && (current?.writer?.writerEpoch ?? null) === target.writerEpoch
        setTerminateModal(null)
        frozenTargetRef.current = null
        if (!sameTarget) {
          // 目标已变：取消并提示用户在原目标上重新确认，绝不误伤新 terminal/writer。
          setDialogNotice(true)
          return
        }
        controller.terminate()
      },
    })
  }

  return (
    <section className="terminal-panel" data-testid="terminal-panel" aria-label={t('shell.title')}>
      <header className="terminal-panel__header">
        <div className="terminal-panel__title">
          <span>{t('shell.title')}</span>
          <span className="terminal-panel__meta">{metaParts.join(' · ')}</span>
        </div>
        <div className="terminal-panel__actions">
          {session != null && environmentReady && !session.hasControl && session.identity != null && session.viewApplied && (
            <Button size="compact" disabled={pending} onClick={() => controller.claim()}>
              {t('shell.control.claim')}
            </Button>
          )}
          {session != null && environmentReady && !session.hasControl && session.identity != null && (
            <Button size="compact" variant="ghost" disabled={pending} onClick={() => controller.takeover()}>
              {t('shell.control.takeover')}
            </Button>
          )}
          {session != null && session.hasControl && (
            <Button size="compact" variant="ghost" disabled={pending} onClick={() => controller.release()}>
              {t('shell.control.release')}
            </Button>
          )}
          {session != null && (
            <Button size="compact" variant="ghost" disabled={pending} onClick={() => controller.refresh()}>
              {t('shell.action.refresh')}
            </Button>
          )}
          {session != null && environmentReady && (session.status === 'EXITED' || session.status === 'FAILED') && (
            <Button size="compact" disabled={pending} onClick={() => controller.restart()}>
              {t('shell.action.restart')}
            </Button>
          )}
          {session != null && environmentReady && session.status === 'RUNNING' && (
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
          <Button size="compact" variant="ghost" onClick={hide}>
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
        onClose={() => setTerminateModal(null)}
      />
    </section>
  )
}
