import { useEffect, useRef, type KeyboardEvent, type Ref, type RefObject } from 'react'
import { KeyValue, KeyValueRow } from '@/shared/ui/data/KeyValue'
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
            <KeyValueRow label={t('ai.runtime.debug.inspector.name')}><code>{selection.tool.name}</code></KeyValueRow>
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
            <KeyValueRow label={t('ai.runtime.debug.inspector.environmentSupport')}><code>{formatEnvSupport(selection.tool.environmentSupport, t)}</code></KeyValueRow>
            {selection.tool.requiredEnvironmentId ? (
              <KeyValueRow label={t('ai.runtime.debug.inspector.requiredEnvId')}><code>{selection.tool.requiredEnvironmentId}</code></KeyValueRow>
            ) : null}
            <KeyValueRow label={t('ai.runtime.debug.inspector.provenance')}><code>{selection.tool.provenance}</code></KeyValueRow>
            <KeyValueRow label={t('ai.runtime.debug.inspector.description')}>{selection.tool.description}</KeyValueRow>
          </KeyValue>
          <div className="thread-debug-payload-section">
            <span className="thread-debug-payload-title">
              {t('ai.runtime.debug.inspector.inputSchemaJson')}
            </span>
            <pre className="thread-event-detail-payload" tabIndex={0}>
              {formatJson(selection.tool.inputSchemaJson)}
            </pre>
          </div>
        </div>
      )}

      {selection.type === 'skill' && (
        <div className="thread-debug-inspector-body">
          <KeyValue>
            <KeyValueRow label={t('ai.runtime.debug.inspector.skillName')}><code>{selection.skill.name}</code></KeyValueRow>
            <KeyValueRow label={t('ai.runtime.debug.inspector.package')}><code>{selection.skill.packageName}</code></KeyValueRow>
            <KeyValueRow label={t('ai.runtime.debug.inspector.delivery')}>
                <Badge tone="success">
                  {formatDelivery(selection.skill.delivery, t)}
                </Badge>
              </KeyValueRow>
            <KeyValueRow label={t('ai.runtime.debug.inspector.path')}><code>{selection.skill.path}</code></KeyValueRow>
            <KeyValueRow label={t('ai.runtime.debug.inspector.description')}>{selection.skill.description}</KeyValueRow>
            <KeyValueRow label={t('ai.runtime.debug.inspector.currentCommit')}><code>{selection.skill.currentCommit}</code></KeyValueRow>
            <KeyValueRow label={t('ai.runtime.debug.inspector.observedHeadCommit')}><code>{selection.skill.observedHeadCommit || '—'}</code></KeyValueRow>
            <KeyValueRow label={t('ai.runtime.debug.inspector.daemonInstalledCommit')}><code>{selection.skill.installedCommit || '—'}</code></KeyValueRow>
          </KeyValue>
          <div className="thread-debug-payload-section">
            <span className="thread-debug-payload-title">
              {t('ai.runtime.debug.inspector.promptXml')}
            </span>
            <pre className="thread-event-detail-payload" tabIndex={0}>
              {selection.skill.promptXml}
            </pre>
          </div>
        </div>
      )}

      {selection.type === 'subagent' && (
        <div className="thread-debug-inspector-body">
          <KeyValue>
            <KeyValueRow label={t('ai.runtime.debug.inspector.name')}><code>{selection.subagent.name}</code></KeyValueRow>
            <KeyValueRow label={t('ai.runtime.debug.inspector.description')}>{selection.subagent.description || '—'}</KeyValueRow>
            <KeyValueRow label={t('ai.runtime.debug.inspector.subagentTools')}>
                {(selection.subagent.tools?.length ?? 0) > 0 ? (
                  <div className="thread-debug-rail" data-testid="subagent-tools-tags">
                    {selection.subagent.tools.map((tool) => (
                      <code key={tool} className="thread-debug-tag">{tool}</code>
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
                        <code key={skillRef} className="thread-debug-tag">{skillRef}</code>
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
                      <code key={subagentName} className="thread-debug-tag">{subagentName}</code>
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
            <pre
              className="thread-event-detail-payload"
              data-testid="subagent-configuration-json"
              tabIndex={0}
            >
              {formatJson(selection.subagent.configurationJson) || '—'}
            </pre>
          </div>
        </div>
      )}

      {selection.type === 'cache' && (
        <div className="thread-debug-inspector-body">
          <KeyValue>
            <KeyValueRow label={t('ai.runtime.debug.inspector.cacheRetention')}>
                <code>{debug?.cacheControl?.retention ?? 'NONE'}</code>
                {(!debug?.cacheControl || debug.cacheControl.retention === 'NONE') ? (
                  <span className="thread-debug-filter-reason">
                    ({t('ai.runtime.debug.inspector.cacheProviderDisclaimer')})
                  </span>
                ) : null}
              </KeyValueRow>
            <KeyValueRow label={t('ai.runtime.debug.inspector.cacheKey')}>
                {debug?.cacheControl?.key ? (
                  <code>{debug.cacheControl.key}</code>
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
              <pre
                className="thread-event-detail-payload"
                tabIndex={0}
                data-testid="frozen-request-json"
              >
                {formatJson(debug.frozenInvocation.requestJson)}
              </pre>
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
            <KeyValueRow label={t('ai.runtime.debug.inspector.providerType')}><code>{selection.preview.providerType}</code></KeyValueRow>
            <KeyValueRow label={t('ai.runtime.debug.inspector.modelName')}><code>{selection.preview.modelName}</code></KeyValueRow>
            <KeyValueRow label={t('ai.runtime.debug.inspector.bodyByteSize')}><code>{formatFileSize(selection.preview.bodyByteSize)} ({selection.preview.bodyByteSize} B)</code></KeyValueRow>
            <KeyValueRow label={t(selection.preview.kind === 'HISTORICAL_REQUEST_PREVIEW'
                ? 'ai.runtime.debug.inspector.recordedAt'
                : 'ai.runtime.debug.inspector.generatedAt')}><code>{selection.preview.generatedAt}</code></KeyValueRow>
            <KeyValueRow label={t('ai.runtime.debug.inspector.sourceHeadEntryId')}><code>{selection.preview.sourceHeadEntryId || '—'}</code></KeyValueRow>
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
            <pre
              className="thread-event-detail-payload"
              tabIndex={0}
              data-testid="preview-request-body"
            >
              {formatJson(selection.preview.bodyJson)}
            </pre>
          </div>
        </div>
      )}
    </section>
  )
}
