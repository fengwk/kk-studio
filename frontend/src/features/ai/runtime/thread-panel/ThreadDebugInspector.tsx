import { useEffect, useRef, type KeyboardEvent, type Ref, type RefObject } from 'react'
import { KeyValue, KeyValueRow } from '@/shared/ui/data/KeyValue'
import { Code, Mono } from '@/shared/ui/data/Mono'
import { Badge } from '@/shared/ui/data/Badge'
import { X } from 'lucide-react'
import type {
  ThreadModelRequestDebugData,
  ThreadModelRequestDebugSkill,
  ThreadModelRequestDebugSubagent,
  ThreadModelRequestDebugTool,
  ThreadProviderRequestPreviewData,
} from '@/features/ai/runtime/thread-timeline-types'
import { formatFileSize } from '@/features/ai/composer'
import { useI18n } from '@/shared/i18n'
import { IconButton } from '@/shared/ui/controls/IconButton'

export type DebugInspectorSelection =
  | { type: 'tool'; tool: ThreadModelRequestDebugTool }
  | { type: 'skill'; skill: ThreadModelRequestDebugSkill }
  | { type: 'subagent'; subagent: ThreadModelRequestDebugSubagent }
  | { type: 'cache' }
  | { type: 'request' }
  | { type: 'preview'; preview: ThreadProviderRequestPreviewData }

function formatJson(raw: string | undefined): string {
  if (!raw) {
    return ''
  }
  try {
    const parsed = JSON.parse(raw)
    return JSON.stringify(parsed, null, 2)
  } catch {
    return raw
  }
}

function formatFilterReason(
  reason: string,
  t: ReturnType<typeof useI18n>['t'],
): string {
  if (reason === 'ENVIRONMENT_NOT_SELECTED') {
    return t('ai.runtime.debug.filterReason.envNotSelected')
  }
  return reason
}

function formatEnvSupport(
  support: 'NONE' | 'OPTIONAL' | 'REQUIRED',
  t: ReturnType<typeof useI18n>['t'],
): string {
  switch (support) {
    case 'NONE':
      return t('ai.runtime.debug.envSupport.none')
    case 'OPTIONAL':
      return t('ai.runtime.debug.envSupport.optional')
    case 'REQUIRED':
      return t('ai.runtime.debug.envSupport.required')
    default:
      return support
  }
}

function formatDelivery(
  delivery: string,
  t: ReturnType<typeof useI18n>['t'],
): string {
  if (delivery === 'LOCAL') {
    return t('ai.runtime.debug.delivery.local')
  }
  if (delivery === 'PLATFORM') {
    return t('ai.runtime.debug.delivery.platform')
  }
  return delivery
}

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
    // 历史条目重放与草稿预览是两件事：标题必须按 kind 区分，不能把历史预览也标成草稿预览。
    title = selection.preview.kind === 'HISTORICAL_REQUEST_PREVIEW'
      ? t('ai.runtime.debug.inspector.historicalPreviewTitle')
      : t('ai.runtime.debug.inspector.draftPreviewTitle')
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

      {selection.type === 'tool' && (
        <div className="thread-debug-inspector-body">
          <KeyValue>
            <KeyValueRow label={t('ai.runtime.debug.inspector.name')}><Mono>{selection.tool.name}</Mono></KeyValueRow>
            <KeyValueRow label={t('ai.runtime.debug.inspector.status')}>
                <Badge tone={selection.tool.state === 'SENT' ? 'neutral' : 'warning'}>
                  {selection.tool.state === 'SENT'
                    ? t('ai.runtime.debug.inspector.toolState.sent')
                    : t('ai.runtime.debug.inspector.toolState.filtered')}
                </Badge>
                {selection.tool.filterReason ? (
                  <span className="thread-debug-filter-reason">
                    ({formatFilterReason(selection.tool.filterReason, t)})
                  </span>
                ) : null}
              </KeyValueRow>
            <KeyValueRow label={t('ai.runtime.debug.inspector.environmentSupport')}><Mono>{formatEnvSupport(selection.tool.environmentSupport, t)}</Mono></KeyValueRow>
            {selection.tool.requiredEnvironmentId ? (
              <KeyValueRow label={t('ai.runtime.debug.inspector.requiredEnvId')}><Mono>{selection.tool.requiredEnvironmentId}</Mono></KeyValueRow>
            ) : null}
            <KeyValueRow label={t('ai.runtime.debug.inspector.provenance')}><Mono>{selection.tool.provenance}</Mono></KeyValueRow>
            <KeyValueRow label={t('ai.runtime.debug.inspector.description')}>{selection.tool.description}</KeyValueRow>
          </KeyValue>
          <div className="thread-debug-payload-section">
            <span className="thread-debug-payload-title">
              {t('ai.runtime.debug.inspector.inputSchemaJson')}
            </span>
            <Code className="thread-event-detail-payload" tabIndex={0}>
              {formatJson(selection.tool.inputSchemaJson)}
            </Code>
          </div>
        </div>
      )}

      {selection.type === 'skill' && (
        <div className="thread-debug-inspector-body">
          <KeyValue>
            <KeyValueRow label={t('ai.runtime.debug.inspector.skillName')}><Mono>{selection.skill.name}</Mono></KeyValueRow>
            <KeyValueRow label={t('ai.runtime.debug.inspector.package')}><Mono>{selection.skill.packageName}</Mono></KeyValueRow>
            <KeyValueRow label={t('ai.runtime.debug.inspector.delivery')}>
                <Badge tone="success">
                  {formatDelivery(selection.skill.delivery, t)}
                </Badge>
              </KeyValueRow>
            <KeyValueRow label={t('ai.runtime.debug.inspector.path')}><Mono>{selection.skill.path}</Mono></KeyValueRow>
            <KeyValueRow label={t('ai.runtime.debug.inspector.description')}>{selection.skill.description}</KeyValueRow>
            <KeyValueRow label={t('ai.runtime.debug.inspector.currentCommit')}><Mono>{selection.skill.currentCommit}</Mono></KeyValueRow>
            <KeyValueRow label={t('ai.runtime.debug.inspector.observedHeadCommit')}><Mono>{selection.skill.observedHeadCommit || '—'}</Mono></KeyValueRow>
            <KeyValueRow label={t('ai.runtime.debug.inspector.daemonInstalledCommit')}><Mono>{selection.skill.installedCommit || '—'}</Mono></KeyValueRow>
          </KeyValue>
          <div className="thread-debug-payload-section">
            <span className="thread-debug-payload-title">
              {t('ai.runtime.debug.inspector.promptXml')}
            </span>
            <Code className="thread-event-detail-payload" tabIndex={0}>
              {selection.skill.promptXml}
            </Code>
          </div>
        </div>
      )}

      {selection.type === 'subagent' && (
        <div className="thread-debug-inspector-body">
          <KeyValue>
            <KeyValueRow label={t('ai.runtime.debug.inspector.name')}><Mono>{selection.subagent.name}</Mono></KeyValueRow>
            <KeyValueRow label={t('ai.runtime.debug.inspector.description')}>{selection.subagent.description || '—'}</KeyValueRow>
            <KeyValueRow label={t('ai.runtime.debug.inspector.subagentTools')}>
                {(selection.subagent.tools?.length ?? 0) > 0 ? (
                  <div className="thread-debug-rail" data-testid="subagent-tools-tags">
                    {selection.subagent.tools.map((tool) => (
                      <Mono key={tool} className="thread-debug-tag">{tool}</Mono>
                    ))}
                  </div>
                ) : (
                  '—'
                )}
              </KeyValueRow>
            <KeyValueRow label={t('ai.runtime.debug.inspector.subagentSkills')}>
                {(selection.subagent.skills?.length ?? 0) > 0 ? (
                  <div className="thread-debug-rail" data-testid="subagent-skills-tags">
                    {selection.subagent.skills.map((skill) => {
                      const skillRef = `${skill.packageName}/${skill.name}`
                      return (
                        <Mono key={skillRef} className="thread-debug-tag">{skillRef}</Mono>
                      )
                    })}
                  </div>
                ) : (
                  '—'
                )}
              </KeyValueRow>
            <KeyValueRow label={t('ai.runtime.debug.inspector.subagentSubagents')}>
                {(selection.subagent.subagents?.length ?? 0) > 0 ? (
                  <div className="thread-debug-rail" data-testid="subagent-subagents-tags">
                    {selection.subagent.subagents.map((subagentName) => (
                      <Mono key={subagentName} className="thread-debug-tag">{subagentName}</Mono>
                    ))}
                  </div>
                ) : (
                  '—'
                )}
              </KeyValueRow>
          </KeyValue>
          <div className="thread-debug-payload-section">
            <span className="thread-debug-payload-title">
              {t('ai.runtime.debug.inspector.subagentConfigurationJson')}
            </span>
            <Code
              className="thread-event-detail-payload"
              data-testid="subagent-configuration-json"
              tabIndex={0}
            >
              {formatJson(selection.subagent.configurationJson) || '—'}
            </Code>
          </div>
        </div>
      )}

      {selection.type === 'cache' && (
        <div className="thread-debug-inspector-body">
          <KeyValue>
            <KeyValueRow label={t('ai.runtime.debug.inspector.cacheRetention')}>
                <Mono>{debug?.cacheControl?.retention ?? 'NONE'}</Mono>
                {(!debug?.cacheControl || debug.cacheControl.retention === 'NONE') ? (
                  <span className="thread-debug-filter-reason">
                    ({t('ai.runtime.debug.inspector.cacheProviderDisclaimer')})
                  </span>
                ) : null}
              </KeyValueRow>
            <KeyValueRow label={t('ai.runtime.debug.inspector.cacheKey')}>
                {debug?.cacheControl?.key ? (
                  <Mono>{debug.cacheControl.key}</Mono>
                ) : (
                  '—'
                )}
              </KeyValueRow>
          </KeyValue>
        </div>
      )}

      {selection.type === 'request' && (
        <div className="thread-debug-inspector-body">
          {debug?.frozenInvocation ? (
            <div>
              <div className="thread-debug-frozen-badge">
                <Badge tone="neutral">
                  {debug.frozenInvocation.kind}
                </Badge>
              </div>
              <Code
                className="thread-event-detail-payload"
                tabIndex={0}
                data-testid="frozen-request-json"
              >
                {formatJson(debug.frozenInvocation.requestJson)}
              </Code>
              <p className="inline-hint">{t('ai.runtime.debug.frozenNotice')}</p>
              <p className="inline-hint">{t('ai.runtime.debug.httpOriginalUnavailable')}</p>
            </div>
          ) : (
            <div className="inline-hint thread-debug-empty-hint">
              {t('ai.runtime.debug.noFrozenInvocation')}
            </div>
          )}
        </div>
      )}

      {selection.type === 'preview' && (
        <div className="thread-debug-inspector-body">
          <div className="thread-debug-frozen-badge">
            <Badge tone="neutral">
              {selection.preview.kind}
            </Badge>
          </div>
          <KeyValue>
            <KeyValueRow label={t('ai.runtime.debug.inspector.providerType')}><Mono>{selection.preview.providerType}</Mono></KeyValueRow>
            <KeyValueRow label={t('ai.runtime.debug.inspector.modelName')}><Mono>{selection.preview.modelName}</Mono></KeyValueRow>
            <KeyValueRow label={t('ai.runtime.debug.inspector.bodyByteSize')}><Mono>{formatFileSize(selection.preview.bodyByteSize)} ({selection.preview.bodyByteSize} B)</Mono></KeyValueRow>
            <KeyValueRow label={t(selection.preview.kind === 'HISTORICAL_REQUEST_PREVIEW'
                ? 'ai.runtime.debug.inspector.recordedAt'
                : 'ai.runtime.debug.inspector.generatedAt')}><Mono>{selection.preview.generatedAt}</Mono></KeyValueRow>
            <KeyValueRow label={t('ai.runtime.debug.inspector.sourceHeadEntryId')}><Mono>{selection.preview.sourceHeadEntryId || '—'}</Mono></KeyValueRow>
            {selection.preview.notice ? (
              <KeyValueRow label={t('ai.runtime.debug.inspector.previewNotice')}>{selection.preview.notice}</KeyValueRow>
            ) : null}
          </KeyValue>
          <p className="inline-hint">{t(selection.preview.kind === 'HISTORICAL_REQUEST_PREVIEW'
            ? 'ai.runtime.debug.historicalNotice'
            : 'ai.runtime.debug.draftNotice')}</p>
          <p className="inline-hint">{t('ai.runtime.debug.httpOriginalUnavailable')}</p>
          <div className="thread-debug-payload-section">
            <span className="thread-debug-payload-title">
              {t('ai.runtime.debug.inspector.requestBodyJson')}
            </span>
            <Code
              className="thread-event-detail-payload"
              tabIndex={0}
              data-testid="preview-request-body"
            >
              {formatJson(selection.preview.bodyJson)}
            </Code>
          </div>
        </div>
      )}
    </section>
  )
}
