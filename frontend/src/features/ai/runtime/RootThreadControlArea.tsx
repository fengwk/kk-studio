import { useMemo } from 'react'
import { GitBranch } from 'lucide-react'
import { ThreadComposer } from '@/features/ai/runtime/thread-panel/ThreadComposer'
import { ThreadLink } from '@/features/ai/runtime/ThreadLink'
import type { useActiveThreadTree } from '@/features/ai/runtime/useActiveThreadTree'
import type { ThreadPanelComposerInput } from '@/features/ai/runtime/thread-panel/ThreadPanel'
import { InteractionCardBody } from '@/features/ai/runtime/interactions/InteractionCardBody'
import { useInteractionsController } from '@/features/ai/runtime/interactions/useInteractionsController'
import { useI18n } from '@/shared/i18n'
import type {
  DialogueMessage,
  QueuedThreadMessage,
} from '@/features/ai/runtime/thread-timeline-types'
import '@/features/ai/runtime/root-thread-control-area.css'

/**
 * 根控制区：执行根草稿、提交、设置、Stop 入口、审批与问卷。
 *
 * 只挂载在根面板（或新建草稿面板）上：草稿、上传注册表和人工执行 Hook 全部由
 * 传入的 composer 契约决定，只读子代理视图不会构造它，因此不会出现任何写入口。
 * 根交互来自 `GET /interactions?rootThreadId=`，卡片按原始调用的 threadId/invocationId
 * 写回，根 ID 只用于过滤。
 */
export function RootThreadControlArea({
  rootThreadId,
  composer,
  messages,
  queuedMessages,
  tree,
}: {
  /** 执行根；null 表示尚未绑定根面板（新建草稿），不查询也不渲染交互卡片。 */
  rootThreadId: string | null
  composer: ThreadPanelComposerInput
  messages: DialogueMessage[]
  queuedMessages: QueuedThreadMessage[]
  /** 执行树（同一份查询）：交互来源的 Thread 名称与代理身份从这里解析。 */
  tree?: ReturnType<typeof useActiveThreadTree>
}) {
  const {
    composerRef,
    parts,
    pending,
    disabled,
    onPartsChange,
    onHistoryPartsChange,
    onSubmit,
    onSubmitGoal,
    onCommand,
    commands,
    focusOnEscape,
    suspended,
    settings,
    scope,
    onPreviewReadinessChange,
    interactionPanel,
  } = composer
  const interactionOpen = interactionPanel != null
  const historicalUserMessages = useMemo(
    () => messages.flatMap((message) =>
      message.role === 'user' && message.text.length > 0 ? [message.text] : [],
    ),
    [messages],
  )
  const queuedUserMessages = useMemo(
    () => queuedMessages.flatMap((message) =>
      message.role === 'user' && message.text.length > 0 ? [message.text] : [],
    ),
    [queuedMessages],
  )
  return (
    <div className="thread-control-area">
      {rootThreadId == null
        ? null
        : <RootInteractionList rootThreadId={rootThreadId} tree={tree} />}
      <ThreadComposer
        ref={composerRef}
        parts={parts}
        pending={pending}
        disabled={disabled}
        onPartsChange={onPartsChange}
        onHistoryPartsChange={onHistoryPartsChange}
        onSubmit={onSubmit}
        onSubmitGoal={onSubmitGoal}
        onCommand={onCommand}
        commands={commands}
        focusOnEscape={focusOnEscape && !interactionOpen}
        active={!interactionOpen && !suspended}
        historicalUserMessages={historicalUserMessages}
        queuedUserMessages={queuedUserMessages}
        settings={settings}
        scope={scope}
        onPreviewReadinessChange={onPreviewReadinessChange}
      />
      {interactionPanel}
    </div>
  )
}

/**
 * 根交互列表：来源标识来自执行树（Thread 名称 + 代理），写回始终使用原始
 * `threadId`/`interactionId`；完整分页保留，刷新失败即使已有旧数据也照常显示，
 * 不把失败伪装成“已无待决交互”。
 */
function RootInteractionList({
  rootThreadId,
  tree,
}: {
  rootThreadId: string
  tree?: ReturnType<typeof useActiveThreadTree>
}) {
  const { t } = useI18n()
  const {
    items,
    isLoading,
    isError,
    hasMore,
    isFetchingMore,
    refresh,
    loadMore,
    removeItem,
  } = useInteractionsController(rootThreadId)

  if (items.length === 0 && !isError) {
    return null
  }
  return (
    <section className="thread-root-interactions" aria-label={t('ai.interaction.pendingTitle')}>
      {isError ? (
        <div className="thread-root-interactions-state is-error" role="alert">
          <span>{t('ai.interaction.loadFailed')}</span>
          <button
            type="button"
            className="ghost-btn"
            disabled={isLoading}
            onClick={() => void refresh()}
          >
            {t('ai.interaction.retry')}
          </button>
        </div>
      ) : null}
      {items.map((item) => {
        const source = tree?.nodesById.get(item.threadId) ?? null
        return (
          <div key={item.interactionId} className="interaction-feed-item">
            <header className="interaction-item-header">
              <div className="interaction-item-meta">
                <span className={`interaction-status-tag ${item.status.toLowerCase()}`}>
                  {item.status === 'WAITING_INPUT'
                    ? t('ai.interaction.waitingInput')
                    : item.status === 'WAITING_APPROVAL'
                      ? t('ai.interaction.waitingApproval')
                      : item.status}
                </span>
                <ThreadLink
                  threadId={item.threadId}
                  className="interaction-source-link"
                  title={item.threadId}
                >
                  <GitBranch size={14} aria-hidden="true" />
                  <span className="interaction-source-name">{source?.name ?? item.threadId}</span>
                </ThreadLink>
                {source ? (
                  <span className="interaction-agent-badge">{source.agentName}</span>
                ) : null}
              </div>
            </header>
            <div className="interaction-item-body">
              <InteractionCardBody
                item={item}
                onSuccess={() => removeItem(item.interactionId)}
              />
            </div>
          </div>
        )
      })}
      {hasMore ? (
        <button
          type="button"
          className="ghost-btn thread-root-interactions-more"
          disabled={isFetchingMore}
          onClick={() => void loadMore()}
        >
          {t('ai.interaction.loadMore')}
        </button>
      ) : null}
    </section>
  )
}
