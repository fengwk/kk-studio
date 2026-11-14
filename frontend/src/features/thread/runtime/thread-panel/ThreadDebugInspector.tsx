import { useEffect, useRef, type KeyboardEvent, type Ref, type RefObject } from 'react'
import { X } from 'lucide-react'
import type {
  ThreadModelRequestDebugData,
  ThreadModelRequestDebugSkill,
  ThreadModelRequestDebugSubagent,
  ThreadModelRequestDebugTool,
  ThreadProviderRequestPreviewData,
} from '@/features/thread/runtime/thread-timeline-types'
import {
  CacheSection,
  PreviewSection,
  RequestSection,
  SkillSection,
  SubagentSection,
  ToolSection,
} from '@/features/thread/runtime/thread-panel/ThreadDebugInspectorSections'
import { useI18n } from '@/shared/i18n'
import { IconButton } from '@/shared/ui/controls/IconButton'

export type DebugInspectorSelection =
  | { type: 'tool'; tool: ThreadModelRequestDebugTool }
  | { type: 'skill'; skill: ThreadModelRequestDebugSkill }
  | { type: 'subagent'; subagent: ThreadModelRequestDebugSubagent }
  | { type: 'cache' }
  | { type: 'request' }
  | { type: 'preview'; preview: ThreadProviderRequestPreviewData }

export function ThreadDebugInspector({
  selection,
  debug,
  onClose,
  closeButtonRef,
  autoFocusCloseButton = true,
}: {
  selection: DebugInspectorSelection
  debug?: ThreadModelRequestDebugData | null
  onClose: () => void
  closeButtonRef?: Ref<HTMLButtonElement>
  autoFocusCloseButton?: boolean
}) {
  const { t } = useI18n()
  const containerRef = useRef<HTMLElement>(null)
  const internalCloseBtnRef = useRef<HTMLButtonElement>(null)
  const resolvedCloseBtnRef = (closeButtonRef as RefObject<HTMLButtonElement | null>) ?? internalCloseBtnRef

  useEffect(() => {
    // 挂载时根据参数安全将焦点引导至详情内部（优先关闭按钮），收敛 Escape 局部处理且不丢焦点
    if (autoFocusCloseButton) {
      const btn = resolvedCloseBtnRef.current
      if (btn) {
        btn.focus({ preventScroll: true })
      } else {
        containerRef.current?.focus({ preventScroll: true })
      }
    }
  }, [autoFocusCloseButton, resolvedCloseBtnRef])

  let title = t('ai.runtime.debug.inspectorTitle')
  if (selection.type === 'tool') {
    title = selection.tool.name
  } else if (selection.type === 'skill') {
    title = selection.skill.name
  } else if (selection.type === 'subagent') {
    title = selection.subagent.name
  } else if (selection.type === 'cache') {
    title = t('ai.runtime.debug.inspector.cacheTitle')
  } else if (selection.type === 'request') {
    title = t('ai.runtime.debug.requestLabel')
  } else if (selection.type === 'preview') {
    title = t('ai.runtime.debug.inspector.historicalPreviewTitle')
  }

  function handleKeyDown(event: KeyboardEvent<HTMLElement>) {
    if (event.nativeEvent.isComposing || event.keyCode === 229) {
      return
    }
    if (event.key === 'Escape') {
      event.preventDefault()
      event.stopPropagation()
      onClose()
    }
  }

  return (
    <section
      ref={containerRef}
      tabIndex={-1}
      className="thread-event-detail thread-debug-inspector"
      aria-label={title}
      data-testid="thread-debug-inspector"
      onKeyDown={handleKeyDown}
    >
      <header className="thread-event-detail-header">
        <h3>{title}</h3>
        <IconButton
          ref={resolvedCloseBtnRef}
          size="compact"
          label={t('ai.runtime.debug.inspectorClose')}
          onClick={onClose}
        >
          <X aria-hidden="true" />
        </IconButton>
      </header>

      {selection.type === 'tool' && <ToolSection tool={selection.tool} />}
      {selection.type === 'skill' && <SkillSection skill={selection.skill} />}
      {selection.type === 'subagent' && <SubagentSection subagent={selection.subagent} />}
      {selection.type === 'cache' && <CacheSection debug={debug} />}
      {selection.type === 'request' && <RequestSection debug={debug} />}
      {selection.type === 'preview' && <PreviewSection preview={selection.preview} />}
    </section>
  )
}
