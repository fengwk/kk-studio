import { ArrowUpRight, Check, CircleX } from 'lucide-react'
import type { ComponentType, ReactNode } from 'react'
import { useState } from 'react'
import { AskUserRecord } from '@/features/thread/runtime/thread-panel/messages/AskUserRecord'
import { ThreadDisclosure } from '@/features/thread/runtime/thread-panel/messages/ThreadDisclosure'
import { ToolCallPreview } from '@/features/thread/runtime/thread-panel/messages/ToolCallPreview'
import { ToolContentView } from '@/features/thread/runtime/thread-panel/messages/ToolContentView'
import { ToolOutputViewport } from '@/features/thread/runtime/thread-panel/messages/ToolOutputViewport'
import {
  discloseText,
  COLLAPSED_MAX_CHARS,
  ERROR_COLLAPSED_LINES,
  isMcpToolName,
  toolDisclosureQuota,
} from '@/features/thread/runtime/thread-panel/messages/disclosure-policy'
import { formatToolCallSummary } from '@/features/thread/runtime/thread-panel/messages/tool-display'
import { DisclosureHint } from '@/features/thread/runtime/thread-panel/messages/ToolDisclosureHint'
import {
  joinTextContents,
  splitContents,
  toolBodyContents,
  windowMutationPreview,
  withoutExactError,
} from '@/features/thread/runtime/thread-panel/messages/tool-message-content'
import {
  buildToolMessageView,
  toolDefaultExpanded,
  toolDisclosurePhase,
} from '@/features/thread/runtime/thread-panel/messages/tool-message-view'
import {
  parseTaskReceipt,
} from '@/features/thread/runtime/state/task-tool-parser'
import { ThreadLink } from '@/features/thread/runtime/panes/ThreadLink'
import { announceTranscriptReading } from '@/features/thread/runtime/thread-timeline/transcript-reading'
import type { ToolDialogueMessage } from '@/features/thread/runtime/thread-timeline/thread-timeline-types'
import type { ToolRendererProps } from '@/platform/extensions/types'
import { useI18n } from '@/shared/i18n'
import { ResourceUriText } from '@/shared/ui/markdown/ResourceUriText'
import './tool-card.css'

/**
 * 统一工具卡片适配器：把工具调用的状态、配额和内容投影到 ThreadDisclosure。
 *
 * - bash 收起时保留最后 10 个逻辑行，MCP 前 5 行；write/edit 审查期间全显，
 *   审查完成后前 10 行；其余工具收起为 header；
 * - 收起文本再限制 1500 Unicode code point；展开与审查全显不受限制；
 * - 错误块固定在 preview 顶部，收起最多 3 行；bash 输出已含错误时不重复；
 * - 用户选择优先，流式转终态不重置；未选择时 write/edit 审查期间默认展开。
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
  const view = buildToolMessageView({
    message,
    result,
    hasCallRecordRenderer: message.toolName === 'ask_user' || Renderer != null,
  })
  const phase = toolDisclosurePhase(view.executionState)
  const quota = toolDisclosureQuota(view.toolName, phase)
  // 用户选择是唯一持久状态；默认值完全由工具与当前结果事实推导，因此流式转终态、
  // 参数增长和重新渲染都不会重置用户的展开选择。
  const [userExpanded, setUserExpanded] = useState<boolean | null>(null)
  const expanded = userExpanded ?? toolDefaultExpanded({ toolName: view.toolName, phase })
  const summary = formatToolCallSummary(view.toolName, view.arguments, { expanded })

  const approval = view.callMessage?.approval ?? message.approval
  const approvalState = !approval?.required
    ? null
    : approval.decision === 'ALLOWED'
      ? t('ai.runtime.approval.allowed')
      : approval.decision === 'DENIED'
        ? t('ai.runtime.approval.denied')
        : t('ai.runtime.approval.requested')
  const rawState = approvalState ? approvalState.replace(/[:：]\s*$/, '') : ''
  const translatedReason = approval?.reason === 'Permission rules require approval'
    ? t('ai.interaction.defaultApprovalReason')
    : approval?.reason
  const notice = approvalState
    ? (translatedReason ? `${rawState} — ${translatedReason}` : approvalState)
    : null
  const normalizedName = view.toolName.trim().toLowerCase()
  const isBash = normalizedName === 'bash'
  const isMcp = isMcpToolName(normalizedName)
  const isTask = normalizedName === 'task'
  const isAskUser = normalizedName === 'ask_user'
  const isMutation = normalizedName === 'write' || normalizedName === 'edit'
  const showTextPreview = isBash || isMcp
  const taskReceipt = isTask ? parseTaskReceipt(view.resultMessage?.details) : null
  const stateLabel = view.executionState === 'environment'
    ? t('ai.runtime.tool.environment', { environment: message.requiredEnvironmentName ?? '' })
    : taskReceipt != null
      ? t(`ai.runtime.tool.task.${taskReceipt.status}`)
      : t(`ai.runtime.tool.${view.executionState}`)

  const errorText = view.errorText?.trim() ? view.errorText : undefined
  const errorTrimmed = errorText?.trim() ?? ''
  const errorIsOutput = errorTrimmed !== ''
    && joinTextContents(view.contents.filter((content) => content.type === 'text')).trim() === errorTrimmed
  // 失败原文被错误块完整承载时，不再在正文中重复同一段文本；bash 的失败说明
  // 通常在输出末尾，由尾部窗口直接显示，因此只在可见输出不含错误时补错误块。
  const contents = (errorTrimmed !== '' && !isBash
    ? view.contents.filter((content) =>
      !(content.type === 'text' && (errorIsOutput || content.text.trim() === errorTrimmed)))
    : view.contents)
    .filter((content) => content.type !== 'text' || content.text !== '')
  const split = splitContents(contents)
  const text = showTextPreview ? joinTextContents(split.text) : ''
  // bash 的失败原文常常就是整份输出：收起和展开都只显示一次输出，而非重复错误块。
  const showErrorBlock = errorText != null
    && !(isBash && text.includes(errorTrimmed))
  const errorWindow = errorText == null
    ? null
    : discloseText(errorText, expanded ? -1 : ERROR_COLLAPSED_LINES)
  // 错误块与普通 preview 共用字符预算，避免失败时显示两份 1500 字符。
  const previewMaxChars = COLLAPSED_MAX_CHARS
    - (showErrorBlock && errorWindow != null ? Array.from(errorWindow.text).length : 0)
  const textWindow = showTextPreview
    ? discloseText(text, expanded ? -1 : quota.preview, isBash ? 'end' : 'start', previewMaxChars)
    : null

  const mutation = isMutation
    ? windowMutationPreview(view.preview, expanded ? -1 : quota.preview, previewMaxChars)
    : null
  const taskPromptAvailable = isTask && Renderer != null && view.hasCallRecordBody
  const askRecordAvailable = isAskUser && view.hasCallRecordBody
  const expandFromHint = (element: HTMLElement) => {
    announceTranscriptReading(element, 'disclosure-hint')
    setUserExpanded(true)
  }

  const bodyContents = toolBodyContents(contents, {
    isTask,
    taskReceipt,
    isAskUser,
    isMutation,
    isMutationSuccess: view.isMutationSuccess,
    showTextPreview,
  })
  const rendererResult =
    Renderer != null
    && view.resultMessage != null
    && (taskReceipt == null || taskReceipt.status === 'completed' || taskReceipt.status === 'failed' || taskReceipt.status === 'cancelled')
  const rendererResultMessage = rendererResult
    ? withoutExactError(view.resultMessage!, errorTrimmed, isBash)
    : undefined
  const hasBody = isMutation
    ? view.isMutationSuccess || bodyContents.length > 0
    : isAskUser
      ? false
      : Renderer != null
        ? rendererResult
        : bodyContents.length > 0

  const mainPreview: ReactNode[] = []
  if (showTextPreview) {
    if (expanded) {
      // 展开态按原始顺序渲染文本与附件，避免把 resource 全部挪到文本之后。
      if (contents.length > 0) {
        mainPreview.push(<ToolContentView key="full-preview" contents={contents} />)
      }
    } else if (textWindow != null) {
      const textHint = (
        <DisclosureHint
          key="text-preview"
          disclosure={textWindow}
          edge={isBash ? 'end' : 'start'}
          onExpand={expandFromHint}
          placement={isBash ? 'before' : 'after'}
        >
          {textWindow.text !== '' ? (
            <ToolOutputViewport bounded={false}>
              <ResourceUriText text={textWindow.text} />
            </ToolOutputViewport>
          ) : null}
        </DisclosureHint>
      )
      if (textWindow.text === '' && quota.preview !== 0
        && (textWindow.hiddenLines > 0 || textWindow.truncated)) {
        // 只有实际隐藏的内容才保留提示，空输出不创建 preview 区域。
        mainPreview.push(textHint)
      } else if (textWindow.text !== '') {
        mainPreview.push(<span key="text-preview" className="thread-tool-text-preview">{textHint}</span>)
      }
    }
  }
  if (mutation != null && (mutation.preview != null || mutation.window?.truncated)) {
    mainPreview.push(
      <span key="mutation-preview" className="thread-tool-mutation-preview">
        {mutation.preview != null ? (
          <ToolCallPreview
            preview={mutation.preview}
            streaming={view.argumentsStreaming}
            variant="transcript"
          />
        ) : null}
        <DisclosureHint
          disclosure={mutation.window}
          edge="start"
          onExpand={expandFromHint}
          placement="after"
        />
      </span>,
    )
  }
  if (taskPromptAvailable && expanded && view.callMessage != null) {
    mainPreview.push(
      <span key="task-prompt">
        <Renderer message={view.callMessage} expanded={expanded} />
      </span>,
    )
  }
  if (askRecordAvailable && expanded) {
    mainPreview.push(
      <span key="ask-record">
        <AskUserRecord arguments={view.arguments} contents={contents} />
      </span>,
    )
  }

  const previewParts: ReactNode[] = []
  if (notice != null) {
    previewParts.push(<p key="approval" className="thread-tool-approval-note">{notice}</p>)
  }
  if (isAskUser && view.executionState === 'input') {
    previewParts.push(
      <p key="answer" className="thread-tool-approval-note">
        {t('ai.runtime.disclosure.waitingForAnswer')}
      </p>,
    )
  }
  if (showErrorBlock && errorWindow != null) {
    previewParts.push(
      <div key="error" className="thread-disclosure-error thread-tool-error">
        <CircleX className="thread-disclosure-error-icon" size={12} aria-hidden="true" />
        <div>
          <p className="thread-disclosure-error-text">{errorWindow.text}</p>
          <DisclosureHint
            disclosure={errorWindow}
            edge="start"
            onExpand={expandFromHint}
            placement="after"
          />
        </div>
      </div>,
    )
  }
  previewParts.push(...mainPreview)
  const preview = previewParts.length > 0 ? <>{previewParts}</> : undefined

  const body = expanded && hasBody ? (
    isMutation ? (
      view.isMutationSuccess ? (
        <>
          <div className="thread-tool-mutation-success">
            <Check size={14} aria-hidden="true" className="mutation-success-icon" />
            <span>{normalizedName === 'write'
              ? t('ai.runtime.mutation.writeSuccess')
              : t('ai.runtime.mutation.editSuccess')}</span>
          </div>
          {bodyContents.length > 0 ? <ToolContentView contents={bodyContents} /> : null}
        </>
      ) : (
        <ToolContentView contents={bodyContents} />
      )
    ) : Renderer != null ? (
      rendererResultMessage != null
        ? <Renderer message={rendererResultMessage} expanded={expanded} />
        : undefined
    ) : bodyContents.length > 0 ? (
      <ToolContentView contents={bodyContents} />
    ) : undefined
  ) : undefined

  const bodyHidden = !expanded && hasBody
  const previewHidden = !expanded && (
    (textWindow != null && quota.preview === 0 && text !== '')
    || (textWindow != null && (textWindow.hiddenLines > 0 || textWindow.truncated))
    || (showTextPreview && split.resources.length > 0)
    || (mutation?.window != null && (mutation.window.hiddenLines > 0 || mutation.window.truncated))
    || taskPromptAvailable
    || askRecordAvailable
  )
  const errorHidden = !expanded && errorWindow != null
    && (errorWindow.hiddenLines > 0 || errorWindow.truncated)
  const hasDetails = bodyHidden || previewHidden || errorHidden

  const actions = taskReceipt != null
    ? (
      <ThreadLink
        className="thread-tool-thread-link"
        threadId={taskReceipt.threadId}
        title={t('ai.runtime.disclosure.viewSubagentExecution')}
        aria-label={t('ai.runtime.disclosure.viewSubagentExecution')}
      >
        <ArrowUpRight aria-hidden="true" />
        <span>{t('ai.runtime.notification.entry.viewSubagentExecution')}</span>
      </ThreadLink>
    )
    : undefined

  const tone = view.hasError
    ? 'error'
    : view.executionState === 'succeeded'
      ? 'success'
      : view.executionState === 'cancelled'
        ? 'neutral'
        : 'pending'

  return (
    <div className={`thread-turn thread-turn-tool tool-state-${view.visualState}`}>
      <ThreadDisclosure
        className="thread-block-tool"
        data-tool-state={view.visualState}
        data-invocation-state={view.executionState}
        aria-busy={view.executionState === 'running'}
        tone={tone}
        expanded={expanded}
        onExpandedChange={setUserExpanded}
        hasDetails={hasDetails}
        preview={preview}
        body={body}
        label={`${summary.name}: ${stateLabel}`}
        header={(
          <span className="thread-tool-summary">
            <span className="thread-tool-name">{summary.name}</span>
            {view.executionState === 'environment'
              ? <span className="thread-tool-state-label"> · {stateLabel}</span> : null}
            {message.invocationStatus === 'READY' && message.queuedBehindToolCallUnfinished
              ? (
                <span className="thread-tool-state-label">
                  {' · '}{t('ai.runtime.tool.waitingForPreviousFileChange')}
                </span>
              ) : null}
            {summary.detail ? (
              <>
                {' '}
                <span className="thread-tool-summary-detail" tabIndex={0}>
                  {summary.detail}
                </span>
              </>
            ) : null}
          </span>
        )}
        actions={actions}
      />
    </div>
  )
}
