import { useEffect, useRef, type KeyboardEvent, type Ref, type RefObject } from 'react'
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
          <dl className="thread-event-detail-rows">
            <div className="thread-event-detail-row">
              <dt>{t('ai.runtime.debug.inspector.name')}</dt>
              <dd><code>{selection.tool.name}</code></dd>
            </div>
            <div className="thread-event-detail-row">
              <dt>{t('ai.runtime.debug.inspector.status')}</dt>
              <dd>
                <span className={selection.tool.state === 'SENT' ? 'status-pill is-neutral' : 'status-pill is-offline'}>
                  {selection.tool.state === 'SENT'
                    ? t('ai.runtime.debug.inspector.toolState.sent')
                    : t('ai.runtime.debug.inspector.toolState.filtered')}
                </span>
                {selection.tool.filterReason ? (
                  <span className="thread-debug-filter-reason">
                    ({formatFilterReason(selection.tool.filterReason, t)})
                  </span>
                ) : null}
              </dd>
            </div>
            <div className="thread-event-detail-row">
              <dt>{t('ai.runtime.debug.inspector.environmentSupport')}</dt>
              <dd><code>{formatEnvSupport(selection.tool.environmentSupport, t)}</code></dd>
            </div>
            {selection.tool.requiredEnvironmentId ? (
              <div className="thread-event-detail-row">
                <dt>{t('ai.runtime.debug.inspector.requiredEnvId')}</dt>
                <dd><code>{selection.tool.requiredEnvironmentId}</code></dd>
              </div>
            ) : null}
            <div className="thread-event-detail-row">
              <dt>{t('ai.runtime.debug.inspector.provenance')}</dt>
              <dd><code>{selection.tool.provenance}</code></dd>
            </div>
            <div className="thread-event-detail-row">
              <dt>{t('ai.runtime.debug.inspector.description')}</dt>
              <dd>{selection.tool.description}</dd>
            </div>
          </dl>
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
          <dl className="thread-event-detail-rows">
            <div className="thread-event-detail-row">
              <dt>{t('ai.runtime.debug.inspector.skillName')}</dt>
              <dd><code>{selection.skill.name}</code></dd>
            </div>
            <div className="thread-event-detail-row">
              <dt>{t('ai.runtime.debug.inspector.package')}</dt>
              <dd><code>{selection.skill.packageName}</code></dd>
            </div>
            <div className="thread-event-detail-row">
              <dt>{t('ai.runtime.debug.inspector.delivery')}</dt>
              <dd>
                <span className="status-pill is-ready">
                  {formatDelivery(selection.skill.delivery, t)}
                </span>
              </dd>
            </div>
            <div className="thread-event-detail-row">
              <dt>{t('ai.runtime.debug.inspector.path')}</dt>
              <dd><code>{selection.skill.path}</code></dd>
            </div>
            <div className="thread-event-detail-row">
              <dt>{t('ai.runtime.debug.inspector.description')}</dt>
              <dd>{selection.skill.description}</dd>
            </div>
            <div className="thread-event-detail-row">
              <dt>{t('ai.runtime.debug.inspector.currentCommit')}</dt>
              <dd><code>{selection.skill.currentCommit}</code></dd>
            </div>
            <div className="thread-event-detail-row">
              <dt>{t('ai.runtime.debug.inspector.observedHeadCommit')}</dt>
              <dd><code>{selection.skill.observedHeadCommit || '—'}</code></dd>
            </div>
            <div className="thread-event-detail-row">
              <dt>{t('ai.runtime.debug.inspector.daemonInstalledCommit')}</dt>
              <dd><code>{selection.skill.installedCommit || '—'}</code></dd>
            </div>
          </dl>
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
          <dl className="thread-event-detail-rows">
            <div className="thread-event-detail-row">
              <dt>{t('ai.runtime.debug.inspector.name')}</dt>
              <dd><code>{selection.subagent.name}</code></dd>
            </div>
            <div className="thread-event-detail-row">
              <dt>{t('ai.runtime.debug.inspector.description')}</dt>
              <dd>{selection.subagent.description || '—'}</dd>
            </div>
          </dl>
        </div>
      )}

      {selection.type === 'cache' && (
        <div className="thread-debug-inspector-body">
          <dl className="thread-event-detail-rows">
            <div className="thread-event-detail-row">
              <dt>{t('ai.runtime.debug.inspector.cacheRetention')}</dt>
              <dd>
                <code>{debug?.cacheControl?.retention ?? 'NONE'}</code>
                {(!debug?.cacheControl || debug.cacheControl.retention === 'NONE') ? (
                  <span className="thread-debug-filter-reason">
                    ({t('ai.runtime.debug.inspector.cacheProviderDisclaimer')})
                  </span>
                ) : null}
              </dd>
            </div>
            <div className="thread-event-detail-row">
              <dt>{t('ai.runtime.debug.inspector.cacheKey')}</dt>
              <dd>
                {debug?.cacheControl?.key ? (
                  <code>{debug.cacheControl.key}</code>
                ) : (
                  '—'
                )}
              </dd>
            </div>
          </dl>
        </div>
      )}

      {selection.type === 'request' && (
        <div className="thread-debug-inspector-body">
          {debug?.frozenInvocation ? (
            <div>
              <div className="thread-debug-frozen-badge">
                <span className="status-pill is-neutral">
                  {debug.frozenInvocation.kind}
                </span>
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
            <span className="status-pill is-neutral">
              {selection.preview.kind}
            </span>
          </div>
          <dl className="thread-event-detail-rows">
            <div className="thread-event-detail-row">
              <dt>{t('ai.runtime.debug.inspector.providerType')}</dt>
              <dd><code>{selection.preview.providerType}</code></dd>
            </div>
            <div className="thread-event-detail-row">
              <dt>{t('ai.runtime.debug.inspector.modelName')}</dt>
              <dd><code>{selection.preview.modelName}</code></dd>
            </div>
            <div className="thread-event-detail-row">
              <dt>{t('ai.runtime.debug.inspector.bodyByteSize')}</dt>
              <dd><code>{formatFileSize(selection.preview.bodyByteSize)} ({selection.preview.bodyByteSize} B)</code></dd>
            </div>
            <div className="thread-event-detail-row">
              <dt>{t(selection.preview.kind === 'HISTORICAL_REQUEST_PREVIEW'
                ? 'ai.runtime.debug.inspector.recordedAt'
                : 'ai.runtime.debug.inspector.generatedAt')}</dt>
              <dd><code>{selection.preview.generatedAt}</code></dd>
            </div>
            <div className="thread-event-detail-row">
              <dt>{t('ai.runtime.debug.inspector.sourceHeadEntryId')}</dt>
              <dd><code>{selection.preview.sourceHeadEntryId || '—'}</code></dd>
            </div>
            {selection.preview.notice ? (
              <div className="thread-event-detail-row">
                <dt>{t('ai.runtime.debug.inspector.previewNotice')}</dt>
                <dd>{selection.preview.notice}</dd>
              </div>
            ) : null}
          </dl>
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
