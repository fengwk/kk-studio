import { useMemo } from 'react'
import { ThreadComposer } from '@/features/ai/runtime/thread-panel/ThreadComposer'
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
}: {
  /** 执行根；null 表示尚未绑定根面板（新建草稿），不查询也不渲染交互卡片。 */
  rootThreadId: string | null
  composer: ThreadPanelComposerInput
  messages: DialogueMessage[]
  queuedMessages: QueuedThreadMessage[]
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
      {rootThreadId == null ? null : <RootInteractionList rootThreadId={rootThreadId} />}
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
        active={!interactionOpen}
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

/** 根交互列表：完整分页，失败可见可重试，不伪装成已无待决交互。 */
function RootInteractionList({ rootThreadId }: { rootThreadId: string }) {
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

  if (isError && items.length === 0) {
    return (
      <div className="thread-root-interactions is-error" role="alert">
        <span>{t('ai.interaction.loadFailed')}</span>
        <button type="button" className="ghost-btn" onClick={() => void refresh()}>
          {t('ai.interaction.retry')}
        </button>
      </div>
    )
  }
  if ((isLoading && items.length === 0) || items.length === 0) {
    return null
  }
  return (
    <section className="thread-root-interactions" aria-label={t('ai.interaction.pendingTitle')}>
      {items.map((item) => (
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
              <span className="interaction-agent-badge">{item.toolName}</span>
            </div>
          </header>
          <div className="interaction-item-body">
            <InteractionCardBody
              item={item}
              onSuccess={() => removeItem(item.interactionId)}
            />
          </div>
        </div>
      ))}
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
