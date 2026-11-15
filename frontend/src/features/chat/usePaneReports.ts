import { useCallback, useEffect, useRef, useState } from 'react'
import { samePaneTarget, type PaneTarget } from '@/features/thread/runtime/agent-pane'
import type { PaneReport } from '@/features/thread/runtime/root-thread/types'
import type { ChatPaneState } from '@/features/chat/chat-pane-state'
import { restoreComposerDraft } from '@/features/thread/composer/composer-draft'
import { partsKey, trimMessageParts } from '@/features/thread/composer/composer-parts'
import { loadPendingAcceptance } from '@/features/thread/runtime/agent-pane'

/** pane 的首次摘要：只看目标 pane 自己的本地草稿与待确认记录，不加载 Thread 快照。 */
function initialPaneReports(chatId: string, paneState: ChatPaneState): Record<string, PaneReport> {
  return Object.fromEntries(
    paneState.panes.map((pane) => [pane.id, initialPaneReport(chatId, pane.id, pane.target)]),
  )
}

/**
 * 首帧摘要只读已绑定目标的本地持久化：直接开在 Thread 上的 pane 不恢复草稿，
 * 否则按该 pane 的 composer scope 取回草稿并算出 draftKey/未发送标记。
 */
function initialPaneReport(chatId: string, paneId: string, target: PaneTarget): PaneReport {
  const parts = target.kind === 'BOUND_THREAD'
    ? []
    : restoreComposerDraft(`agent-pane:CHAT:${chatId}:${paneId}`, [])
  return {
    target,
    draftKey: partsKey(parts),
    pending: loadPendingAcceptance({ kind: 'CHAT', id: chatId }, paneId, undefined, target) != null,
    hasUnsentDraft: trimMessageParts(parts).length > 0,
    sessionId: target.kind === 'NEW_THREAD_DRAFT' ? target.sessionId : null,
    branchName: target.kind === 'NEW_THREAD_DRAFT' ? target.threadName : null,
  }
}

export interface PaneReportsController {
  paneReports: Record<string, PaneReport>
  /** 与 paneReports 同步的镜像：分支命名流程读“提交时最新摘要”。 */
  reportsRef: React.RefObject<Record<string, PaneReport>>
  handlePaneReport: (paneId: string, report: PaneReport) => void
}

/**
 * 各 pane 自报的运行时摘要。摘要内容未变时不替换状态，避免无谓重渲染；
 * 首帧按目标 pane 的本地草稿初始化，绝不因为页面加载而拉起 Thread 快照。
 */
export function usePaneReports(chatId: string, paneState: ChatPaneState): PaneReportsController {
  const [paneReports, setPaneReports] = useState<Record<string, PaneReport>>(
    () => initialPaneReports(chatId, paneState),
  )
  const reportsRef = useRef(paneReports)
  useEffect(() => { reportsRef.current = paneReports }, [paneReports])

  const handlePaneReport = useCallback((paneId: string, report: PaneReport) => {
    setPaneReports((current) => {
      const previous = current[paneId]
      if (
        previous != null
        && previous.pending === report.pending
        && previous.hasUnsentDraft === report.hasUnsentDraft
        && previous.draftKey === report.draftKey
        && previous.sessionId === report.sessionId
        && previous.branchName === report.branchName
        && samePaneTarget(previous.target, report.target)
      ) {
        return current
      }
      return { ...current, [paneId]: report }
    })
  }, [])

  return { paneReports, reportsRef, handlePaneReport }
}
