import { ArrowUpRight, CircleX } from 'lucide-react'
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
  disclosureHint,
  toolPreviewLines,
} from '@/features/thread/runtime/thread-panel/messages/disclosure-policy'
import { formatToolCallSummary } from '@/features/thread/runtime/thread-panel/messages/tool-display'
import { DisclosureHint } from '@/features/thread/runtime/thread-panel/messages/ToolDisclosureHint'
import {
  bashOutput,
  joinTextContents,
  splitContents,
  toolBodyContents,
  windowMutationPreview,
  withoutExactError,
} from '@/features/thread/runtime/thread-panel/messages/tool-message-content'
import { buildToolMessageView } from '@/features/thread/runtime/thread-panel/messages/tool-message-view'
import { useApprovalReviewVisibleElsewhere } from '@/features/thread/runtime/interactions/approval-review-context'
import { parseTaskReceipt } from '@/features/thread/runtime/state/task-tool-parser'
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
 * bash/write/edit 的短窗口不随详情展开或执行阶段改变；完整记录单独展示。
 * bash 的错误只与最终可见的尾部窗口去重。
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
  const previewLines = toolPreviewLines(view.toolName)
  const [expanded, setExpanded] = useState(false)
  // 同一调用是否已有一张可见审批卡；只有该调用自己的卡片影响去重。
  const approvalCardVisible = useApprovalReviewVisibleElsewhere(message.invocationId)
  const summary = formatToolCallSummary(view.toolName, view.arguments, { expanded })

  const normalizedName = view.toolName.trim().toLowerCase()
  const isBash = normalizedName === 'bash'
  const isMcp = isMcpToolName(normalizedName)
  const isTask = normalizedName === 'task'
  const isAskUser = normalizedName === 'ask_user'
  const isMutation = normalizedName === 'write' || normalizedName === 'edit'
  const showTextPreview = isBash || isMcp
  const taskReceipt = isTask ? parseTaskReceipt(view.resultMessage?.details) : null
  const bash = isBash
    ? bashOutput(joinTextContents(splitContents(view.contents).text), view.resultMessage?.details, view.hasError)
    : null
  const stateLabel = bash?.timedOut
    ? t('ai.runtime.tool.timedOut')
    : view.executionState === 'environment'
    ? t('ai.runtime.tool.environment', { environment: message.requiredEnvironmentName ?? '' })
    : view.executionState === 'waiting-child'
      ? t('ai.runtime.tool.waitingChild')
      : taskReceipt != null
      ? t(`ai.runtime.tool.task.${taskReceipt.status}`)
      : t(`ai.runtime.tool.${view.executionState}`)

  const errorText = bash?.note ?? (view.errorText?.trim() ? view.errorText : undefined)
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
  const text = bash?.text ?? (isMcp ? joinTextContents(split.text) : '')
  const bashWindow = isBash ? discloseText(text, previewLines, 'end') : null
  const showErrorBlock = errorText != null && !bashWindow?.text.includes(errorTrimmed)
  const errorWindow = showErrorBlock && errorText != null
    ? discloseText(errorText, ERROR_COLLAPSED_LINES)
    : null
  // 先用完整预算判断 bash 错误是否可见，只有独立错误槽位占用预算时才重算窗口。
  const previewMaxChars = COLLAPSED_MAX_CHARS - Array.from(errorWindow?.text ?? '').length
  const textWindow = isBash
    ? (showErrorBlock ? discloseText(text, previewLines, 'end', previewMaxChars) : bashWindow)
    : isMcp ? discloseText(text, previewLines, 'start', previewMaxChars) : null
  // 同一调用已有可见审批卡时，冻结审查材料只在审批卡里出现一次：工具卡退化为
  // 名称 + 目标 + 等待审批。未加载的审批卡和只读视图不会让工具卡丢失 diff。
  const reviewShownInApprovalCard = isMutation
    && view.executionState === 'approval'
    && approvalCardVisible
  const summaryDetail = reviewShownInApprovalCard ? view.preview?.path : summary.detail
  const mutation = isMutation && !reviewShownInApprovalCard
    ? windowMutationPreview(view.preview, previewLines, previewMaxChars)
    : null
  const mutationHint = mutation?.window ? disclosureHint(mutation.window, 'end') : null
  const mutationDetails = mutationHint != null || mutation?.hasFullDetails === true
  const bashVisible = isBash && Boolean(textWindow?.text.trim())
  const bashDetails = bash != null && !bash.incomplete && text !== ''
    && (!bashVisible || textWindow?.hiddenLines !== 0 || textWindow.truncated)
  const taskPromptAvailable = isTask && Renderer != null && view.hasCallRecordBody
  const askRecordAvailable = isAskUser && view.hasCallRecordBody
  const expandFromHint = (element: HTMLElement) => {
    announceTranscriptReading(element, 'disclosure-hint')
    setExpanded(true)
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
  const hasRendererBody = !isAskUser && (rendererResultMessage?.contents.some((content) =>
    content.type !== 'text' || content.text !== '') ?? false)

  const mainPreview: ReactNode[] = []
  if (bashVisible && textWindow != null) {
    // 对仅有摘录的流不能把可计算的已接收行数说成总省略行数。
    const hint = disclosureHint(bash?.incomplete ? { ...textWindow, hiddenLines: 0 } : textWindow, 'end')
    mainPreview.push(
      <div key="bash-preview" className="thread-tool-text-preview thread-tool-short-preview">
        {bash?.incomplete ? <span className="thread-disclosure-hint">{t('ai.runtime.disclosure.outputOmitted')}</span> : null}
        {hint ? <span className="thread-disclosure-hint">{hint}</span> : null}
        <ToolOutputViewport bounded={false}><ResourceUriText text={textWindow.text} /></ToolOutputViewport>
      </div>,
    )
  }
  if (isMcp) {
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
          edge="start"
          onExpand={expandFromHint}
          placement="after"
        >
          {textWindow.text !== '' ? (
            <ToolOutputViewport bounded={false}>
              <ResourceUriText text={textWindow.text} />
            </ToolOutputViewport>
          ) : null}
        </DisclosureHint>
      )
      if (textWindow.text === '' && (textWindow.hiddenLines > 0 || textWindow.truncated)) {
        // 只有实际隐藏的内容才保留提示，空输出不创建 preview 区域。
        mainPreview.push(textHint)
      } else if (textWindow.text !== '') {
        mainPreview.push(<span key="text-preview" className="thread-tool-text-preview">{textHint}</span>)
      }
    }
  }
  if (mutation != null && (mutation.preview != null || mutationHint != null)) {
    mainPreview.push(
      <div key="mutation-preview" className="thread-tool-short-preview">
        {mutationHint ? <span className="thread-disclosure-hint">{mutationHint}</span> : null}
        {mutation.preview != null ? (
          <ToolCallPreview
            preview={mutation.preview}
            streaming={view.argumentsStreaming}
            variant="transcript"
          />
        ) : null}
      </div>,
    )
  }
  const errorHidden = errorWindow != null && (errorWindow.hiddenLines > 0 || errorWindow.truncated)
  const error = errorWindow != null ? (
    <div className="thread-disclosure-error thread-tool-error">
      <CircleX className="thread-disclosure-error-icon" size={12} aria-hidden="true" />
      <p className="thread-disclosure-error-text">
        {errorWindow.text}{errorHidden ? '…' : null}
      </p>
    </div>
  ) : undefined

  const bashBody = isBash ? (bashDetails
    ? bash?.note ? [{ type: 'text' as const, text }, ...split.resources] : contents
    : split.resources) : []
  const mcpDetails = isMcp && (split.resources.length > 0
    || (textWindow != null && (textWindow.hiddenLines > 0 || textWindow.truncated)))
  const hasBody = bashBody.length > 0 || bash?.storedPath != null || mutationDetails || errorHidden
    || bodyContents.length > 0 || hasRendererBody || taskPromptAvailable || askRecordAvailable
  const body = expanded && hasBody ? (
    <div
      className="thread-tool-details"
      role="region"
      tabIndex={0}
      aria-label={t('ai.runtime.disclosure.fullDetails')}
    >
      {errorHidden && !(bashDetails && errorIsOutput)
        ? <ToolOutputViewport bounded={false}>{errorText}</ToolOutputViewport> : null}
      {bashBody.length > 0 ? <ToolContentView contents={bashBody} /> : null}
      {bash?.storedPath ? (
        <ToolOutputViewport bounded={false}>
          {t('ai.runtime.disclosure.storedLogPath', { path: bash.storedPath })}
        </ToolOutputViewport>
      ) : null}
      {mutationDetails ? <ToolCallPreview preview={view.preview} streaming={false} variant="transcript" /> : null}
      {taskPromptAvailable && view.callMessage != null ? <Renderer message={view.callMessage} expanded /> : null}
      {askRecordAvailable ? <AskUserRecord arguments={view.arguments} contents={contents} /> : null}
      {Renderer != null && hasRendererBody && rendererResultMessage != null
        ? <Renderer message={rendererResultMessage} expanded />
        : bodyContents.length > 0 ? <ToolContentView contents={bodyContents} /> : null}
    </div>
  ) : undefined

  const taskThreadId = taskReceipt?.threadId
    ?? (isTask && view.resultMessage == null ? message.childThreadId : null)
  const actions = taskThreadId != null
    ? (
      <ThreadLink
        className="thread-tool-thread-link"
        threadId={taskThreadId}
        title={t('ai.runtime.disclosure.viewSubagentExecution')}
        aria-label={t('ai.runtime.disclosure.viewSubagentExecution')}
      >
        <ArrowUpRight aria-hidden="true" />
        <span>{t('ai.runtime.notification.entry.viewSubagentExecution')}</span>
      </ThreadLink>
    )
    : undefined

  const showState = bash?.timedOut || ['environment', 'waiting-child', 'approval', 'input', 'failed', 'cancelled', 'unknown']
    .includes(view.executionState)

  return (
    <div className="thread-turn thread-turn-tool">
      <ThreadDisclosure
        className="thread-block-tool"
        data-tool-state={view.visualState}
        data-invocation-state={view.executionState}
        aria-busy={view.executionState === 'running'}
        tone="neutral"
        expanded={expanded}
        onExpandedChange={setExpanded}
        hasDetails={!reviewShownInApprovalCard && (hasBody || mcpDetails)}
        error={reviewShownInApprovalCard ? undefined : error}
        preview={!reviewShownInApprovalCard && mainPreview.length > 0 ? <>{mainPreview}</> : undefined}
        body={reviewShownInApprovalCard ? undefined : body}
        label={`${summary.name}: ${stateLabel}`}
        header={(
          <span className="thread-tool-summary">
            <span className="thread-tool-name">{summary.name}</span>
            {showState ? <span className="thread-tool-state-label"> · {stateLabel}</span> : null}
            {message.invocationStatus === 'READY' && message.queuedBehindToolCallUnfinished
              ? (
                <span className="thread-tool-state-label">
                  {' · '}{t('ai.runtime.tool.waitingForPreviousFileChange')}
                </span>
              ) : null}
            {summaryDetail ? (
              <>
                {' '}
                <span className="thread-tool-summary-detail" tabIndex={0}>
                  {summaryDetail}
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
