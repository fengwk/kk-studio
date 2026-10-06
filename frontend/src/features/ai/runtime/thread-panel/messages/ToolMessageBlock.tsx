import { ChevronDown, ChevronRight } from 'lucide-react'
import type { ComponentType } from 'react'
import { useState } from 'react'
import { AskUserRecord } from '@/features/ai/runtime/thread-panel/messages/AskUserRecord'
import { ToolCallPreview } from '@/features/ai/runtime/thread-panel/messages/ToolCallPreview'
import { ToolContentView } from '@/features/ai/runtime/thread-panel/messages/ToolContentView'
import {
  buildToolMessageView,
  shouldShowErrorNotice,
  toolDefaultExpanded,
  toolErrorFacts,
  toolMessageContents,
} from '@/features/ai/runtime/thread-panel/messages/tool-message-view'
import { useReadImageDefault } from '@/features/ai/runtime/thread-panel/messages/useReadImageDefault'
import { announceTranscriptReading } from '@/features/ai/runtime/transcript-reading'
import type { ToolDialogueMessage } from '@/features/ai/runtime/thread-timeline-types'
import type { ToolRendererProps } from '@/platform/extensions/types'
import { useI18n } from '@/shared/i18n'
import './tool-card.css'

/**
 * 统一工具卡片外壳：一张卡片只有 Header、必要的调用内容与结果内容。
 *
 * - 展开按钮位于第一行最右侧（收起 `>` / 展开 `v`），存在有意义正文的卡片都可收起，
 *   包括默认可见的 prompt 与 diff；
 * - Header 已展示的参数不在正文重复，正文也不添加「参数/结果」标题；
 * - 默认可见性只由工具与首次结果事实决定（read 图片预览、write 正文、edit diff、
 *   bash 持续输出、task/ask_user 正文），用户主动选择优先，流式转终态复用同一身份
 *   不重置选择；
 * - 失败摘要与人工等待状态始终可见，正文被收起也不消失。
 */
export function ToolMessageBlock({
  message,
  result,
  renderer: Renderer,
}: {
  message: ToolDialogueMessage
  result?: ToolDialogueMessage
  renderer?: ComponentType<ToolRendererProps>
}) {
  const { t } = useI18n()
  const contents = toolMessageContents(message, result)
  const isAskUser = message.toolName === 'ask_user'
  // 用户选择是唯一持久状态；默认值完全由工具与当前结果事实推导，因此流式转终态、
  // 参数增长和重新渲染都不会重置用户的展开选择。
  const [userExpanded, setUserExpanded] = useState<boolean | null>(null)
  const readImageDefault = useReadImageDefault(message.toolName, contents)
  // 失败事实来自调用与结果的合并视图：调用本身失败时也必须按错误态渲染。
  const facts = toolErrorFacts({ message, result })
  const expanded =
    userExpanded
    ?? toolDefaultExpanded({
      toolName: message.toolName,
      contents,
      hasError: facts.hasError,
      mediaDefault: readImageDefault,
    })
  const view = buildToolMessageView({
    message,
    result,
    expanded,
    hasCallRecordRenderer: isAskUser || Renderer != null,
  })
  const approval = view.callMessage?.approval ?? message.approval
  const approvalState = !approval?.required
    ? null
    : approval.decision === 'ALLOWED'
      ? t('ai.runtime.approval.allowed')
      : approval.decision === 'DENIED'
        ? t('ai.runtime.approval.denied')
        : t('ai.runtime.approval.requested')
  const notice = approvalState
    ? (approval?.reason ? `${approvalState} — ${approval.reason}` : approvalState)
    : null
  const errorNotice = view.errorText
  const bodyTexts = view.contents.flatMap((content) =>
    content.type === 'text' ? [content.text] : []
  )
  const showErrorNotice = errorNotice != null
    && shouldShowErrorNotice({
      showBody: view.showBody,
      bodyTexts,
      errorNotice,
    })

  return (
    <div className={`thread-turn thread-turn-tool tool-state-${view.visualState}`}>
      <section
        className="thread-block thread-block-tool"
        data-tool-state={view.visualState}
        aria-busy={view.visualState === 'pending'}
      >
        <div className="thread-tool-surface">
          <div className={`thread-tool-header${view.hasBody ? ' has-toggle' : ''}`}>
            <span className="thread-tool-summary">
              <span className="thread-tool-name">{view.summary.name}</span>
              {view.summary.detail ? (
                // 参数在可用宽度内折行：完整原文留在 DOM 中可选中复制，不横向滚动，
                // 也不撑破 pane（tabIndex 让键盘可聚焦这段参数区）。
                <span className="thread-tool-summary-detail" tabIndex={0}>
                  {view.summary.detail}
                </span>
              ) : null}
            </span>
            {view.hasBody ? (
              <button
                type="button"
                className="thread-tool-toggle"
                aria-expanded={expanded}
                aria-label={
                  expanded
                    ? t('ai.runtime.message.collapseTool')
                    : t('ai.runtime.message.expandTool')
                }
                title={
                  expanded
                    ? t('ai.runtime.message.collapseTool')
                    : t('ai.runtime.message.expandTool')
                }
                onClick={(event) => {
                  // 展开/收起是用户对只读卡片的交互意图：暂停外层自动贴底，
                  // 之后到达的流式文本不会把用户正在看的卡片拉走。
                  announceTranscriptReading(event.currentTarget, 'tool-toggle')
                  setUserExpanded(!expanded)
                }}
              >
                {expanded
                  ? <ChevronDown aria-hidden="true" />
                  : <ChevronRight aria-hidden="true" />}
              </button>
            ) : null}
          </div>
          {notice ? <p className="thread-tool-approval-note">{notice}</p> : null}
          {view.showBody && view.hasBody ? (
            <div className="thread-tool-body">
              <ToolBody view={view} Renderer={Renderer} expanded={expanded} />
            </div>
          ) : null}
          {showErrorNotice ? <p className="thread-tool-error">{errorNotice}</p> : null}
        </div>
      </section>
    </div>
  )
}

function ToolBody({
  view,
  Renderer,
  expanded,
}: {
  view: ReturnType<typeof buildToolMessageView>
  Renderer?: ComponentType<ToolRendererProps>
  expanded: boolean
}) {
  // 只有 bash 的持续日志跟随尾部；其余静态正文（结果 Text/JSON、write/edit/task
  // 参数）一律从顶部读，内容增长也不强拉到底部。
  const followTail = view.toolName.trim().toLowerCase() === 'bash' && view.contentsStreaming
  if (view.toolName === 'ask_user') {
    return <AskUserRecord arguments={view.arguments} contents={view.contents} />
  }
  return (
    <>
      {view.hasCallPreview && view.preview ? (
        <ToolCallPreview preview={view.preview} streaming={view.argumentsStreaming} />
      ) : null}
      {view.hasCallRecordBody && Renderer && view.callMessage ? (
        <div className="thread-tool-call-body">
          <Renderer message={view.callMessage} expanded={expanded} />
        </div>
      ) : null}
      {view.resultMessage && Renderer ? (
        <Renderer message={view.resultMessage} expanded={expanded} />
      ) : null}
      {!Renderer && view.contents.length > 0 ? (
        <ToolContentView contents={view.contents} followTail={followTail} />
      ) : null}
    </>
  )
}
