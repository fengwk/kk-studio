import { useMemo, useRef } from 'react'
import {
  extractContextWindow,
  type AgentModelView,
} from '@/features/ai/catalog'
import { buildThreadTimeline, isThreadWorking } from '@/features/ai/runtime/thread-timeline'
import { buildThreadEventTimeline } from '@/features/ai/runtime/thread-events'
import { aggregateEntryUsage } from '@/features/ai/runtime/thread-timeline/turn-usage'
import { useAgentThreadQueries } from '@/features/ai/runtime/useAgentThreadQueries'
import { useHarnessThreadRealtime } from '@/features/ai/runtime/useHarnessThreadRealtime'
import { translate } from '@/shared/i18n'

/** 只读 Thread 投影的完整形状：控制面（controller）是它的超集。 */
export type ThreadProjection = ReturnType<typeof useThreadProjection>

/**
 * 只读 Thread 投影：快照查询、实时流、timeline/events、usage 与运行时展示标签。
 *
 * 该 hook 不持有任何编辑草稿、上传注册表或人工执行 mutation，因此子代理观察视图
 * 可以只挂载它；根控制区在此基础上再叠加 {@link useAgentThreadController} 的控制面。
 * 一份投影只有这一个所有者，控制面与只读视图共用同一结果，不重复查询与订阅。
 */
export function useThreadProjection(threadId: string) {
  const {
    agents,
    models,
    thread,
    sessionId,
    entries,
    queuedCommands,
    modelInvocation,
    toolInvocations,
    modelAttemptFailures,
    stopReceipts,
    manualCompaction,
    snapshotQuery,
  } = useAgentThreadQueries(threadId)
  const bound = Boolean(thread)
  const realtime = useHarnessThreadRealtime(
    threadId,
    Boolean(threadId) && snapshotQuery.isSuccess,
    thread?.version,
    modelInvocation,
    toolInvocations,
    modelAttemptFailures,
  )
  const timeline = buildThreadTimeline(
    entries,
    queuedCommands,
    toolInvocations,
    realtime.modelStream,
    realtime.toolStreams,
    modelAttemptFailures,
    modelInvocation,
  )
  // Footer 累计用量直接由全部 Entry 事实派生：不依赖对话消息、卡片 visible 或 TURN_END 投影，
  // compaction 等隐藏内容里真实发生的 usage 也一并进入累计。
  const branchUsage = aggregateEntryUsage(entries)
  // Event 投影独立于 DialogueMessage：durable Entry 全类型 + 活跃 model/tool overlay。
  // useMemo 保证快照未变化时 events 引用稳定（Pane 的 selected/active 跟随 effect 依赖它）。
  const events = useMemo(
    () => buildThreadEventTimeline({
      entries,
      modelInvocation,
      toolInvocations,
      modelAttemptFailures,
      modelStream: realtime.modelStream,
      toolStreams: realtime.toolStreams,
    }),
    [entries, modelAttemptFailures, modelInvocation, realtime.modelStream, realtime.toolStreams, toolInvocations],
  )
  const working = isThreadWorking(thread, timeline)
  const runtimeLabels = resolveRuntimeLabels(thread, models)
  const bodyRef = useRef<HTMLDivElement>(null)
  // 根判定：执行根是 parentThreadId 为 null 的 Thread；未加载完成前为 null，控制区不暴露。
  const isRoot = thread == null ? null : thread.parentThreadId == null

  return {
    threadId,
    sessionId,
    agents,
    models,
    thread,
    bound,
    isRoot,
    title: thread?.threadId || translate('ai.chat.chatLabel'),
    timeline,
    events,
    runtimeLabels,
    branchUsage,
    working,
    entries,
    messagesLoading: snapshotQuery.isLoading,
    messagesError: snapshotQuery.error,
    bodyRef,
    queuedCommands,
    manualCompaction,
    stopReceipts,
    modelInvocation,
    toolInvocations,
    modelAttemptFailures,
    snapshotQuery,
  }
}

function resolveRuntimeLabels(
  thread: ReturnType<typeof useAgentThreadQueries>['thread'],
  models: AgentModelView[],
) {
  const settings = thread?.branchSettings
  const model = models.find(
    (item) =>
      item.providerName === settings?.model.providerName
      && item.name === settings?.model.modelName,
  )
  const contextWindow = extractContextWindow(model)
  const environmentName = settings?.environmentName ?? null
  return {
    environmentName,
    contextWindow,
  }
}
