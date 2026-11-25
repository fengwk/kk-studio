import { useMemo, type ReactNode } from 'react'
import { ThreadComposer } from '@/features/thread/runtime/thread-panel/ThreadComposer'
import type { ThreadPanelComposerInput } from '@/features/thread/runtime/thread-panel/ThreadPanel'
import { InteractionCardBody } from '@/features/thread/runtime/interactions/InteractionCardBody'
import { InteractionOwnerSource } from '@/features/thread/runtime/interactions/InteractionOwnerSource'
import { useRootInteractions } from '@/features/thread/runtime/interactions/root-interactions'
import { useI18n } from '@/shared/i18n'
import { Button } from '@/shared/ui/controls/Button'
import { interactionIdentity, manualInteractions } from '@/shared/lib/interactions'
import type {
  DialogueContent,
  DialogueMessage,
  QueuedThreadMessage,
} from '@/features/thread/runtime/thread-timeline/thread-timeline-types'
import { createResourcePart, createTextPart } from '@/features/thread/composer/composer-parts'
import '@/features/thread/runtime/panes/root-thread-control-area.css'

/**
 * 根控制区：执行根草稿、提交、设置、Stop 入口、审批与问卷。
 *
 * 只挂载在根面板（或新建草稿面板）上：草稿、上传注册表和人工执行 Hook 全部由
 * 传入的 composer 契约决定，只读子代理视图不会构造它，因此不会出现任何写入口。
 * 根交互来自 `GET /interactions?rootThreadId=`（由执行根 pane 的
 * `RootInteractionsProvider` 统一持有），卡片按原始调用的 threadId/invocationId
 * 写回，根 ID 只用于过滤。
 */
export function RootThreadControlArea({
  rootThreadId,
  composer,
  messages,
  queuedMessages,
  notice,
  hidden = false,
}: {
  /** 执行根；null 表示尚未绑定根面板（新建草稿），不查询也不渲染交互卡片。 */
  rootThreadId: string | null
  composer: ThreadPanelComposerInput
  messages: DialogueMessage[]
  queuedMessages: QueuedThreadMessage[]
  /** 输入框上方的轻量入口/提示（如空白草稿 pane 的“继续已有会话”），不参与 composer 状态。 */
  notice?: ReactNode
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
    sendDisabled,
    onPartsChange,
    onHistoryPartsChange,
    onSubmit,
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
    () => messages.flatMap((message) => {
      if (message.role !== 'user') {
        return []
      }
      const contents = message.contents ?? [{ type: 'text', text: message.text }]
      const parts = composerParts(contents)
      return message.text.length > 0 || parts.length > 0 ? [{ text: message.text, parts }] : []
    }),
    [messages],
  )
  const queuedUserMessages = useMemo(
    () => queuedMessages.flatMap((message) => {
      if (message.role !== 'user') {
        return []
      }
      const contents = message.contents ?? [{ type: 'text', text: message.text }]
      const parts = composerParts(contents)
      return message.text.length > 0 || parts.length > 0 ? [{ text: message.text, parts }] : []
    }),
    [queuedMessages],
  )
  return (
    <div
      className={hidden ? 'thread-control-area debug-hidden' : 'thread-control-area'}
      inert={hidden}
    >
      {rootThreadId == null
        ? null
        : <RootInteractionList />}
      {notice}
      <ThreadComposer
        ref={composerRef}
        parts={parts}
        pending={pending}
        disabled={disabled}
        sendDisabled={sendDisabled}
        onPartsChange={onPartsChange}
        onHistoryPartsChange={onHistoryPartsChange}
        onSubmit={onSubmit}
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

/** 历史草稿重建：文本仍走文本 part，只有具备持久资源 id 的附件才参与回填。 */
function composerParts(contents: DialogueContent[]) {
  return contents.flatMap((content) => {
    if (content.type === 'text') {
      return [createTextPart(content.text)]
    }
    return content.attachment.blobId
      ? [createResourcePart(
        content.attachment.blobId,
        content.attachment.name,
        content.attachment.preview,
      )]
      : []
  })
}

/**
 * 根交互列表：渲染可操作的人工等待（问卷/审批），归属来自会话归属 SPI，显示与全局待处理页
 * 一致；写回始终使用原始 `threadId`/`toolInvocationId`，完整分页保留，刷新失败即使已有旧数据
 * 也照常显示，不把失败伪装成“已无待决交互”。
 *
 * 条目来自执行根 pane 的 `RootInteractionsProvider`：同一份列表也是时间线判断
 * 「同一调用的审批卡是否可见」的唯一依据，两边共享同一个 controller。
 *
 * 环境等待不在这里重复呈现第二张提醒卡：它是 `(真实执行根, 所需环境)` 聚合，没有可回写的调用，
 * 只在全局待处理页与工具状态行呈现。过滤后仍保留 `loadMore`，避免首页只有环境等待时
 * 把后续页的人工等待一并藏起来。
 */
export function RootInteractionList() {
  const { t } = useI18n()
  const interactions = useRootInteractions()
  if (interactions == null) {
    return null
  }
  const {
    items,
    isLoading,
    isError,
    hasMore,
    isFetchingMore,
    refresh,
    loadMore,
    removeItem,
  } = interactions

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
            variant="secondary"
            disabled={isLoading}
            onClick={() => void refresh()}
          >
            {t('ai.interaction.retry')}
          </Button>
        </div>
      ) : null}
      {manualItems.map((item) => (
        <div key={interactionIdentity(item)} className="interaction-feed-item">
          <div className="interaction-item-body">
            <InteractionOwnerSource item={item} />
            <InteractionCardBody item={item} onSuccess={() => removeItem(item.toolInvocationId)} />
          </div>
        </div>
      ))}
      {hasMore ? (
        <Button
          variant="secondary"
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
