import { Button } from '@/shared/ui/controls/Button'
import { Checkbox } from '@/shared/ui/controls/Checkbox'
import { FieldLabel } from '@/shared/ui/controls/FieldLabel'
import { NumberInput } from '@/shared/ui/controls/NumberInput'
import { TextArea } from '@/shared/ui/controls/TextArea'
import { TextInput } from '@/shared/ui/controls/TextInput'
import { Mono } from '@/shared/ui/data/Mono'
import { Dialog } from '@/shared/ui/overlays/Dialog'
import { useI18n } from '@/shared/i18n'
import type { FormEvent } from 'react'
import { MCP_NAME_PATTERN_SOURCE } from './mcp-utils'
import type { CreateMcpServerFormState } from './mcp-server-forms'

const HEADERS_PLACEHOLDER = '{\n  "Authorization": "Bearer ${AUTH_TOKEN}"\n}'

/** 新建 MCP Server 弹窗：只呈现表单，校验与提交由页面编排。 */
export function CreateMcpServerDialog({
  form,
  pending,
  onChange,
  onSubmit,
  onRequestClose,
}: {
  form: CreateMcpServerFormState
  pending: boolean
  onChange: (form: CreateMcpServerFormState) => void
  onSubmit: (event: FormEvent) => void
  onRequestClose: () => void
}) {
  const { t } = useI18n()

  /** 任何编辑都清除上一条校验错误，错误只在提交后成立。 */
  function edit(patch: Partial<CreateMcpServerFormState>) {
    onChange({ ...form, ...patch, error: null })
  }

  return (
    <Dialog
      className="mcp-modal-card"
      title={t('ai.mcp.create')}
      pending={pending}
      onClose={onRequestClose}
    >
      <form className="modal-card-form" onSubmit={onSubmit}>
        <div className="modal-body">
          <label className="form-group">
            <FieldLabel required>{t('ai.mcp.name')}</FieldLabel>
            <TextInput
              value={form.name}
              onChange={(event) => edit({ name: event.target.value })}
              placeholder={t('ai.mcp.namePlaceholder')}
              maxLength={32}
              disabled={pending}
              required
              autoFocus
            />
            <span className="inline-hint">
              {t('ai.mcp.nameHint')}{' '}
              <Mono className="mcp-name-pattern">{MCP_NAME_PATTERN_SOURCE}</Mono>
            </span>
          </label>

          <label className="form-group">
            <FieldLabel required>{t('ai.mcp.url')}</FieldLabel>
            <TextInput
              value={form.url}
              onChange={(event) => edit({ url: event.target.value })}
              placeholder="https://example.com/mcp"
              maxLength={2048}
              disabled={pending}
              required
            />
            <span className="inline-hint">{t('ai.mcp.urlHint')}</span>
          </label>

          <label className="form-group">
            <FieldLabel>{t('ai.mcp.headers')}</FieldLabel>
            <TextArea
              className="mcp-headers-textarea"
              value={form.headersText}
              onChange={(event) => edit({ headersText: event.target.value })}
              placeholder={HEADERS_PLACEHOLDER}
              disabled={pending}
              rows={4}
            />
            <span className="inline-hint">{t('ai.mcp.headersHint')}</span>
          </label>

          <label className="form-group">
            <FieldLabel>{t('ai.mcp.timeout')}</FieldLabel>
            <NumberInput
              value={String(form.timeoutMillis)}
              min={1}
              onChange={(timeoutMillis) => edit({ timeoutMillis })}
              aria-label={t('ai.mcp.timeout')}
              disabled={pending}
            />
          </label>

          <Checkbox
            checked={form.enabled}
            onChange={(enabled) => edit({ enabled })}
            disabled={pending}
            label={t('ai.mcp.enabled')}
          />

          {form.error && (
            <p className="field-error" role="alert">
              {form.error}
            </p>
          )}
        </div>

        <div className="modal-footer">
          <Button variant="ghost" onClick={onRequestClose} disabled={pending}>
            {t('shared.cancel')}
          </Button>
          <Button type="submit" loading={pending}>
            {t('ai.catalog.action.confirmCreate')}
          </Button>
        </div>
      </form>
    </Dialog>
  )
}
