import { KeyValue, KeyValueRow } from '@/shared/ui/data/KeyValue'
import { Code, Mono } from '@/shared/ui/data/Mono'
import { Badge } from '@/shared/ui/data/Badge'
import type {
  ThreadModelRequestDebugData,
  ThreadModelRequestDebugSkill,
  ThreadModelRequestDebugSubagent,
  ThreadModelRequestDebugTool,
  ThreadProviderRequestPreviewData,
} from '@/features/thread/runtime/thread-timeline/thread-timeline-types'
import { formatByteCount } from '@/shared/lib/format-bytes'
import { useI18n } from '@/shared/i18n'
import { formatRawJsonText } from '@/features/thread/runtime/thread-timeline/payload-json'
import { formatEnvSupport, formatJson } from '@/features/thread/runtime/thread-panel/thread-debug-json'

/**
 * Inspector 的六个详情体，各对应 `selection.type` 的一个分支。
 *
 * 每个 section 只读自己的 selection 子类型（cache/request 读 debug 快照），
 * 不含内部状态与副作用；类型分派留在 ThreadDebugInspector，
 * 典型的新增一种 selection 只需加一个 section 与一行分派。
 */

export function ToolSection({ tool }: { tool: ThreadModelRequestDebugTool }) {
  const { t } = useI18n()
  return (
    <div className="thread-debug-inspector-body">
      <KeyValue>
        <KeyValueRow label={t('ai.runtime.debug.inspector.name')}><Mono>{tool.name}</Mono></KeyValueRow>
        <KeyValueRow label={t('ai.runtime.debug.inspector.environmentSupport')}><Mono>{formatEnvSupport(tool.environmentSupport, t)}</Mono></KeyValueRow>
        <KeyValueRow label={t('ai.runtime.debug.inspector.provenance')}><Mono>{tool.provenance}</Mono></KeyValueRow>
        <KeyValueRow label={t('ai.runtime.debug.inspector.description')}>{tool.description}</KeyValueRow>
      </KeyValue>
      <div className="thread-debug-payload-section">
        <span className="thread-debug-payload-title">
          {t('ai.runtime.debug.inspector.inputSchema')}
        </span>
        <Code className="thread-event-detail-payload" tabIndex={0}>
          {formatJson(tool.inputSchema)}
        </Code>
      </div>
    </div>
  )
}

export function SkillSection({ skill }: { skill: ThreadModelRequestDebugSkill }) {
  const { t } = useI18n()
  return (
    <div className="thread-debug-inspector-body">
      <KeyValue>
        <KeyValueRow label={t('ai.runtime.debug.inspector.skillName')}><Mono>{skill.name}</Mono></KeyValueRow>
        <KeyValueRow label={t('ai.runtime.debug.inspector.package')}><Mono>{skill.packageName}</Mono></KeyValueRow>
        <KeyValueRow label={t('ai.runtime.debug.inspector.description')}>{skill.description}</KeyValueRow>
      </KeyValue>
      {skill.promptXml !== null && (
        <div className="thread-debug-payload-section">
          <span className="thread-debug-payload-title">
            {t('ai.runtime.debug.inspector.promptXml')}
          </span>
          <Code className="thread-event-detail-payload" tabIndex={0}>
            {skill.promptXml}
          </Code>
        </div>
      )}
    </div>
  )
}

export function SubagentSection({ subagent }: { subagent: ThreadModelRequestDebugSubagent }) {
  const { t } = useI18n()
  return (
    <div className="thread-debug-inspector-body">
      <KeyValue>
        <KeyValueRow label={t('ai.runtime.debug.inspector.name')}><Mono>{subagent.name}</Mono></KeyValueRow>
        <KeyValueRow label={t('ai.runtime.debug.inspector.description')}>{subagent.description || '—'}</KeyValueRow>
        <KeyValueRow label={t('ai.runtime.debug.inspector.subagentTools')}>
            {(subagent.tools?.length ?? 0) > 0 ? (
              <div className="thread-debug-rail" data-testid="subagent-tools-tags">
                {subagent.tools.map((tool) => (
                  <Mono key={tool} className="thread-debug-tag">{tool}</Mono>
                ))}
              </div>
            ) : (
              '—'
            )}
          </KeyValueRow>
        <KeyValueRow label={t('ai.runtime.debug.inspector.subagentSkills')}>
            {(subagent.skills?.length ?? 0) > 0 ? (
              <div className="thread-debug-rail" data-testid="subagent-skills-tags">
                {subagent.skills.map((skill) => {
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
            {(subagent.subagents?.length ?? 0) > 0 ? (
              <div className="thread-debug-rail" data-testid="subagent-subagents-tags">
                {subagent.subagents.map((subagentName) => (
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
          {formatJson(subagent.configuration) || '—'}
        </Code>
      </div>
    </div>
  )
}

export function CacheSection({ debug }: { debug?: ThreadModelRequestDebugData | null }) {
  const { t } = useI18n()
  return (
    <div className="thread-debug-inspector-body">
      <KeyValue>
        <KeyValueRow label={t('ai.runtime.debug.inspector.cacheRetention')}>
            <Mono>{debug?.cacheControl?.retention ?? 'NONE'}</Mono>
            {(!debug?.cacheControl || debug.cacheControl.retention === 'NONE') ? (
              <span className="thread-debug-disclaimer">
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
  )
}

export function RequestSection({ debug }: { debug?: ThreadModelRequestDebugData | null }) {
  const { t } = useI18n()
  return (
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
            {formatRawJsonText(debug.frozenInvocation.requestJson)}
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
  )
}

export function PreviewSection({ preview }: { preview: ThreadProviderRequestPreviewData }) {
  const { t } = useI18n()
  return (
    <div className="thread-debug-inspector-body">
      <div className="thread-debug-frozen-badge">
        <Badge tone="neutral">
          {preview.kind}
        </Badge>
      </div>
      <KeyValue>
        <KeyValueRow label={t('ai.runtime.debug.inspector.providerType')}><Mono>{preview.providerType}</Mono></KeyValueRow>
        <KeyValueRow label={t('ai.runtime.debug.inspector.modelName')}><Mono>{preview.modelName}</Mono></KeyValueRow>
        <KeyValueRow label={t('ai.runtime.debug.inspector.bodyByteSize')}><Mono>{formatByteCount(preview.bodyByteSize)} ({preview.bodyByteSize} B)</Mono></KeyValueRow>
        <KeyValueRow label={t('ai.runtime.debug.inspector.recordedAt')}><Mono>{preview.generatedAt}</Mono></KeyValueRow>
        <KeyValueRow label={t('ai.runtime.debug.inspector.sourceHeadEntryId')}><Mono>{preview.sourceHeadEntryId || '—'}</Mono></KeyValueRow>
        {preview.notice ? (
          <KeyValueRow label={t('ai.runtime.debug.inspector.previewNotice')}>{preview.notice}</KeyValueRow>
        ) : null}
      </KeyValue>
      <p className="inline-hint">{t('ai.runtime.debug.historicalNotice')}</p>
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
          {formatRawJsonText(preview.bodyJson)}
        </Code>
      </div>
    </div>
  )
}
