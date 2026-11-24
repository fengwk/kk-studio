import type { FormEvent } from 'react'
import { RefreshCw } from 'lucide-react'
import { Button } from '@/shared/ui/controls/Button'
import { Checkbox } from '@/shared/ui/controls/Checkbox'
import { FieldLabel } from '@/shared/ui/controls/FieldLabel'
import { NumberInput } from '@/shared/ui/controls/NumberInput'
import { TextArea } from '@/shared/ui/controls/TextArea'
import { TextInput } from '@/shared/ui/controls/TextInput'
import { LoadingIndicator } from '@/shared/ui/feedback/LoadingSpinner'
import { StateBlock } from '@/shared/ui/feedback/StateBlock'
import { Dialog } from '@/shared/ui/overlays/Dialog'
import { useUnsavedChangesConfirmation } from '@/shared/ui/overlays/useUnsavedChangesConfirmation'
import { useI18n } from '@/shared/i18n'
import { hasUnsavedEditChanges, type EditMcpServerFormState } from './mcp-server-forms'

const HEADERS_PLACEHOLDER = '{\n  "Authorization": "Bearer ${AUTH_TOKEN}"\n}'

/** 编辑 MCP Server 弹窗：详情加载、表单与“发现工具”动作由页面编排后传入。 */
export function EditMcpServerDialog({
  form,
  saving,
  onChange,
  onSubmit,
  onDiscover,
  onRetryLoad,
  onRequestClose,
}: {
  form: EditMcpServerFormState
  saving: boolean
  onChange: (form: EditMcpServerFormState) => void
  onSubmit: (event: FormEvent) => void
  onDiscover: () => void
  onRetryLoad: () => void
  onRequestClose: () => void
}) {
  const { t } = useI18n()
  const busy = saving || form.discoverPending
  const { requestClose, confirmation } = useUnsavedChangesConfirmation(
    hasUnsavedEditChanges(form),
    busy,
    onRequestClose,
  )

  /** 任何编辑都清除上一条校验错误，错误只在提交后成立。 */
  function edit(patch: Partial<EditMcpServerFormState>) {
    onChange({ ...form, ...patch, error: null })
  }

  return (
    <>
      <Dialog
        className="mcp-modal-card"
        title={t('ai.mcp.editTitle', { name: form.server.name })}
        pending={busy}
        onClose={requestClose}
      >
        {form.loading ? (
          <div className="modal-body">
            <LoadingIndicator label={t('ai.mcp.loadingConfig')} />
          </div>
        ) : form.fetchError ? (
          <div className="modal-body">
            <StateBlock title={form.fetchError || t('ai.mcp.loadConfigFailed')} tone="danger" />
            <div style={{ marginTop: 12, textAlign: 'center' }}>
              <Button variant="secondary" onClick={onRetryLoad}>
                {t('shared.retry')}
              </Button>
            </div>
          </div>
        ) : (
          <form className="modal-card-form" onSubmit={onSubmit}>
            <div className="modal-body">
              <label className="form-group">
                <FieldLabel>{t('ai.mcp.name')}</FieldLabel>
                <TextInput value={form.server.name} disabled readOnly />
              </label>

              <label className="form-group">
                <FieldLabel required>{t('ai.mcp.url')}</FieldLabel>
                <TextInput
                  value={form.url}
                  onChange={(event) => edit({ url: event.target.value })}
                  placeholder="https://example.com/mcp"
                  maxLength={2048}
                  disabled={busy}
                  required
                  autoFocus
                />
              </label>

              <label className="form-group">
                <FieldLabel>{t('ai.mcp.headers')}</FieldLabel>
                <TextArea
                  className="mcp-headers-textarea"
                  value={form.headersText}
                  onChange={(event) =>
                    edit({ headersText: event.target.value, clearHeaders: false })
                  }
                  placeholder={HEADERS_PLACEHOLDER}
                  disabled={busy || form.clearHeaders}
                  rows={4}
                />
                <span className="inline-hint">
                  {form.loaded?.headersConfigured
                    ? t('ai.mcp.headersKeepHint')
                    : t('ai.mcp.headersHint')}
                </span>
                {form.loaded?.headersConfigured && (
                  <Checkbox
                    checked={form.clearHeaders}
                    onChange={(clearHeaders) =>
                      edit({
                        clearHeaders,
                        headersText: clearHeaders ? '' : form.headersText,
                      })
                    }
                    disabled={busy}
                    label={t('ai.mcp.clearHeaders')}
                  />
                )}
              </label>

              <label className="form-group">
                <FieldLabel>{t('ai.mcp.timeout')}</FieldLabel>
                <NumberInput
                  value={String(form.timeoutMillis)}
                  min={1}
                  onChange={(timeoutMillis) => edit({ timeoutMillis })}
                  aria-label={t('ai.mcp.timeout')}
                  disabled={busy}
                />
              </label>

              <Checkbox
                checked={form.enabled}
                onChange={(enabled) => edit({ enabled })}
                disabled={busy}
                label={t('ai.mcp.enabled')}
              />

              {form.error && (
                <p className="field-error" role="alert">
                  {form.error}
                </p>
              )}
            </div>

            <div className="modal-footer modal-footer-with-leading-action">
              <Button
                variant="secondary"
                className="modal-footer-leading-action"
                onClick={onDiscover}
                disabled={saving}
                loading={form.discoverPending}
              >
                {!form.discoverPending && <RefreshCw aria-hidden="true" />}
                {t('ai.mcp.discover')}
              </Button>
              <Button variant="secondary" onClick={requestClose} disabled={busy}>
                {t('shared.cancel')}
              </Button>
              <Button type="submit" disabled={form.discoverPending} loading={saving}>
                {t('ai.catalog.action.saveChanges')}
              </Button>
            </div>
          </form>
        )}
      </Dialog>
      {confirmation}
    </>
  )
}
