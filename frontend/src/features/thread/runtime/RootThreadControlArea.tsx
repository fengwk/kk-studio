import { useMemo } from 'react'
import { ThreadComposer } from '@/features/thread/runtime/thread-panel/ThreadComposer'
import type { ThreadPanelComposerInput } from '@/features/thread/runtime/thread-panel/ThreadPanel'
import { InteractionCardBody } from '@/features/thread/runtime/interactions/InteractionCardBody'
import { InteractionOwnerSource } from '@/features/thread/runtime/interactions/InteractionOwnerSource'
import { useInteractionsController } from '@/features/thread/runtime/interactions/useInteractionsController'
import { useI18n } from '@/shared/i18n'
import { Button } from '@/shared/ui/controls/Button'
import { interactionIdentity, manualInteractions } from '@/shared/lib/interactions'
import type {
  DialogueMessage,
  QueuedThreadMessage,
} from '@/features/thread/runtime/thread-timeline-types'
import '@/features/thread/runtime/root-thread-control-area.css'

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
  hidden = false,
}: {
  /** 执行根；null 表示尚未绑定根面板（新建草稿），不查询也不渲染交互卡片。 */
  rootThreadId: string | null
  composer: ThreadPanelComposerInput
  messages: DialogueMessage[]
  queuedMessages: QueuedThreadMessage[]
  /**
   * Debug 主视图激活时为真：整个输入/审批/分支控制区保持挂载但完全隐藏且惰性
   * （草稿、上传注册表与绑定原地保留，退出 Debug 立即恢复，不重新初始化）。
   */
  hidden?: boolean
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
    onCommandPaletteVisibilityChange,
    focusOnEscape,
    suspended,
    settings,
    scope,
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
    <div
      className={hidden ? 'thread-control-area debug-hidden' : 'thread-control-area'}
      inert={hidden}
    >
      {rootThreadId == null
        ? null
        : <RootInteractionList rootThreadId={rootThreadId} />}
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
        onCommandPaletteVisibilityChange={onCommandPaletteVisibilityChange}
        focusOnEscape={focusOnEscape && !interactionOpen && !hidden && !suspended}
        active={!interactionOpen && !suspended && !hidden}
        restoreOnActivate={focusOnEscape !== false && !suspended}
        historicalUserMessages={historicalUserMessages}
        queuedUserMessages={queuedUserMessages}
        settings={settings}
        scope={scope}
      />
      {interactionPanel}
    </div>
  )
}

/**
 * 根交互列表：只渲染可操作的人工等待（问卷/审批），归属来自会话归属 SPI，显示与全局待处理页
 * 一致；写回始终使用原始 `threadId`/`interactionId`，完整分页保留，刷新失败即使已有旧数据
 * 也照常显示，不把失败伪装成“已无待决交互”。
 *
 * <p>环境等待不在这里重复呈现第二张提醒卡：它是 `(真实执行根, 所需环境)` 聚合，没有可回写的调用，
 * 只在全局待处理页与工具状态行（W3 切片）呈现。过滤后仍保留 `loadMore`，避免首页只有环境等待时
 * 把后续页的人工等待一并藏起来。
 */
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

  const manualItems = manualInteractions(items)
  if (manualItems.length === 0 && !isError && !hasMore) {
    return null
  }
  return (
    <section className="thread-root-interactions" aria-label={t('ai.interaction.pendingTitle')}>
      {isError ? (
        <div className="thread-root-interactions-state is-error" role="alert">
          <span>{t('ai.interaction.loadFailed')}</span>
          <Button
            variant="ghost"
            disabled={isLoading}
            onClick={() => void refresh()}
          >
            {t('ai.interaction.retry')}
          </Button>
        </div>
      ) : null}
      {manualItems.map((item) => (
        <div key={interactionIdentity(item)} className="interaction-feed-item">
          <header className="interaction-item-header">
            <div className="interaction-item-meta">
              <InteractionOwnerSource item={item} />
            </div>
          </header>
          <div className="interaction-item-body">
            <InteractionCardBody item={item} onSuccess={() => removeItem(item.interactionId!)} />
          </div>
        </div>
      ))}
      {hasMore ? (
        <Button
          variant="ghost"
          className="thread-root-interactions-more"
          disabled={isFetchingMore}
          onClick={() => void loadMore()}
        >
          {t('ai.interaction.loadMore')}
        </Button>
      ) : null}
    </section>
  )
}
