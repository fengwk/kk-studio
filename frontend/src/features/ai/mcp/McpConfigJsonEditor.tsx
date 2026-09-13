import { useState } from 'react'
import { FieldLabel } from '@/shared/ui/console/FieldLabel'
import { useI18n } from '@/shared/i18n'
import type { EnvironmentCardDTO } from '@/shared/api/contracts/ai-environment'
import {
  formatMcpConfigJson,
  validateMcpConfigJson,
  extractDraftConnectionType,
  extractDraftEnvironmentId,
  updateLocalEnvironmentIdInJson,
  REMOTE_CONFIG_TEMPLATE,
  createLocalConfigTemplate,
} from './mcp-config-json'

export interface McpConfigJsonEditorProps {
  value: string
  onChange: (value: string) => void
  environments: EnvironmentCardDTO[]
  disabled?: boolean
  error?: string | null
  ariaLabel?: string
  rows?: number
}

/**
 * 权威单份 MCP 配置 JSON 编辑器：
 * 统一承载模板切换、格式化、校验、Local 环境选择器与文本域交互。
 */
export function McpConfigJsonEditor({
  value,
  onChange,
  environments,
  disabled = false,
  error = null,
  ariaLabel,
  rows = 12,
}: McpConfigJsonEditorProps) {
  const { t } = useI18n()
  const [validationMessage, setValidationMessage] = useState<string | null>(null)
  const [validationTone, setValidationTone] = useState<'success' | 'error' | null>(null)

  const handleApplyTemplate = (template: string) => {
    setValidationMessage(null)
    setValidationTone(null)
    onChange(template)
  }

  const handleFormat = () => {
    try {
      const formatted = formatMcpConfigJson(value)
      setValidationMessage(null)
      setValidationTone(null)
      onChange(formatted)
    } catch (err) {
      setValidationMessage(err instanceof Error ? err.message : String(err))
      setValidationTone('error')
    }
  }

  const handleValidate = () => {
    const res = validateMcpConfigJson(value)
    if (res.valid) {
      setValidationMessage(t('ai.mcp.validationPassed'))
      setValidationTone('success')
    } else {
      setValidationMessage(res.error ?? 'Invalid JSON')
      setValidationTone('error')
    }
  }

  const handleTextareaChange = (nextValue: string) => {
    setValidationMessage(null)
    setValidationTone(null)
    onChange(nextValue)
  }

  const draftType = extractDraftConnectionType(value)
  const draftEnvId = extractDraftEnvironmentId(value)

  return (
    <div className="form-group">
      <FieldLabel required>{t('ai.mcp.configJson')}</FieldLabel>
      <div className="mcp-json-toolbar">
        <button
          type="button"
          className="ghost-btn btn-sm"
          disabled={disabled}
          onClick={() => handleApplyTemplate(REMOTE_CONFIG_TEMPLATE)}
        >
          {t('ai.mcp.remoteTemplate')}
        </button>
        <button
          type="button"
          className="ghost-btn btn-sm"
          disabled={disabled}
          onClick={() => {
            const firstEnvId = environments[0]?.id
            handleApplyTemplate(createLocalConfigTemplate(firstEnvId))
          }}
        >
          {t('ai.mcp.localTemplate')}
        </button>
        <button
          type="button"
          className="ghost-btn btn-sm"
          disabled={disabled}
          onClick={handleFormat}
        >
          {t('ai.mcp.formatJson')}
        </button>
        <button
          type="button"
          className="ghost-btn btn-sm"
          disabled={disabled}
          onClick={handleValidate}
        >
          {t('ai.mcp.validateJson')}
        </button>
      </div>

      {draftType === 'local' && (
        <label className="form-group" style={{ marginBottom: 8 }}>
          <FieldLabel>{t('ai.mcp.envSelect')}</FieldLabel>
          <select
            aria-label={t('ai.mcp.envSelect')}
            disabled={disabled}
            value={draftEnvId ?? ''}
            onChange={(e) => {
              const selected = e.target.value
              if (selected) {
                const rewritten = updateLocalEnvironmentIdInJson(value, selected)
                handleTextareaChange(rewritten)
              }
            }}
          >
            <option value="" disabled>
              -- {t('ai.mcp.envSelect')} --
            </option>
            {environments.map((env) => (
              <option key={env.id} value={env.id}>
                {env.name} ({env.id.slice(0, 8)}...)
              </option>
            ))}
          </select>
        </label>
      )}

      <textarea
        className="code-textarea mcp-config-json-textarea"
        aria-label={ariaLabel ?? t('ai.mcp.configJson')}
        disabled={disabled}
        value={value}
        onChange={(e) => handleTextareaChange(e.target.value)}
        placeholder="{}"
        rows={rows}
        required
      />

      {validationMessage && (
        <div
          className={`mcp-validation-msg is-${validationTone ?? 'info'}`}
          role={validationTone === 'error' ? 'alert' : 'status'}
        >
          {validationMessage}
        </div>
      )}

      {error && (
        <p className="field-error" role="alert">
          {error}
        </p>
      )}
    </div>
  )
}
