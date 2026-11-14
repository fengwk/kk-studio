import { useLayoutEffect, useMemo, useRef, useState } from 'react'
import { Badge } from '@/shared/ui/data/Badge'
import { Code, FormInput, Lock } from 'lucide-react'
import { Button } from '@/shared/ui/controls/Button'
import { Checkbox } from '@/shared/ui/controls/Checkbox'
import { FieldLabel } from '@/shared/ui/controls/FieldLabel'
import { Select } from '@/shared/ui/controls/Select'
import { TextArea } from '@/shared/ui/controls/TextArea'
import { TextInput } from '@/shared/ui/controls/TextInput'
import { Dialog } from '@/shared/ui/overlays/Dialog'
import { isConflictError, request } from '@/shared/api/client'
import { useI18n } from '@/shared/i18n'
import { MAX_PLUGIN_VALUES_UTF8_BYTES } from './plugin-config-json'
import { parsePluginValuesSchema } from './plugin-schema'
import { SchemaElementRenderer } from './SchemaElementRenderer'
import { usePluginSecretDraft } from './usePluginSecretDraft'
import { usePluginValuesEditor } from './usePluginValuesEditor'
import './plugin-config.css'
import type {
  PluginConfigurationUpdateRequestDTO,
  PluginDTO,
  SecretUpdateOperation,
} from '@/shared/api/generated/contracts'

interface PluginConfigModalProps {
  plugin: PluginDTO
  onClose: () => void
  onSuccess: (updated: PluginDTO) => void
}

export function PluginConfigModal({
  plugin,
  onClose,
  onSuccess,
}: PluginConfigModalProps) {
  const { t } = useI18n()
  const isDeepLink = plugin.authKind?.type === 'DEEP_LINK'
  const version = plugin.configuration.version

  const {
    secretOperations,
    secretValues,
    clearConfirmed,
    clearSecretValues,
    changeOperation,
    changeSecretValue,
    changeClearConfirmed,
    buildSecretPatch,
  } = usePluginSecretDraft({
    configuredSecretNames: Object.keys(plugin.configuration.secrets),
    isDeepLink,
  })

  const {
    valuesJson,
    replaceValuesJson,
    fieldDrafts,
    fieldErrors,
    editor,
    parseResult,
    hasFieldErrors,
    firstFieldError,
    valuesTooLarge,
    draftsBlockForm,
    structureGeneration,
    readingFiles,
    saveError,
    setSaveError,
  } = usePluginValuesEditor({ initialValuesJson: plugin.configuration.valuesJson })

  const [pending, setPending] = useState(false)
  const [conflictError, setConflictError] = useState(false)
  const mountedRef = useRef(false)
  const busyRef = useRef(false)

  // 解析 valuesSchema
  const schemaResult = useMemo(
    () => parsePluginValuesSchema(plugin.configuration.valuesSchema),
    [plugin.configuration.valuesSchema],
  )

  const [editorMode, setEditorMode] = useState<'form' | 'json'>(() =>
    schemaResult.ok ? 'form' : 'json',
  )

  useLayoutEffect(() => {
    mountedRef.current = true
    return () => {
      mountedRef.current = false
    }
  }, [])

  const formModeBlockedByDrafts = editorMode === 'form' && draftsBlockForm

  async function handleSave() {
    if (pending || busyRef.current || readingFiles) return

    if (hasFieldErrors) {
      setSaveError(firstFieldError ?? null)
      return
    }

    if (!parseResult.ok) {
      setSaveError(parseResult.error)
      return
    }

    if (valuesTooLarge) {
      setSaveError(
        t('plugins.configDialog.valuesTooLarge', { max: MAX_PLUGIN_VALUES_UTF8_BYTES }),
      )
      return
    }

    const secretPatchResult = buildSecretPatch()
    if (!secretPatchResult.ok) {
      setSaveError(
        secretPatchResult.reason === 'mustConfirmClear'
          ? t('plugins.configDialog.mustConfirmClear', { key: secretPatchResult.key })
          : t('plugins.configDialog.secretRequired', { key: secretPatchResult.key }),
      )
      return
    }
    const secretPatch = secretPatchResult.patch

    const payload: PluginConfigurationUpdateRequestDTO = {
      expectedVersion: version,
      valuesJson,
      secretPatch: Object.keys(secretPatch).length > 0 ? secretPatch : undefined,
    }

    busyRef.current = true
    clearSecretValues()
    setPending(true)
    setSaveError(null)
    setConflictError(false)

    try {
      const updated = await request(
        'PUT /api/platform/plugins/{pluginId}/configuration',
        { path: { pluginId: plugin.pluginId }, body: payload },
      )
      if (!mountedRef.current) return
      clearSecretValues()
      onSuccess(updated)
    } catch (err) {
      if (!mountedRef.current) return
      if (isConflictError(err)) {
        setConflictError(true)
      } else {
        setSaveError(err instanceof Error ? err.message : String(err))
      }
    } finally {
      if (mountedRef.current) {
        setPending(false)
      }
      busyRef.current = false
    }
  }

  function handleClose() {
    if (!pending && !busyRef.current) {
      clearSecretValues()
      onClose()
    }
  }

  const hasSecrets = Object.keys(plugin.configuration.secrets).length > 0

  return (
    <Dialog
      className="resource-modal-card plugin-config-modal modal-touch-target"
      title={t('plugins.configDialog.title', { name: plugin.name })}
      onClose={handleClose}
      pending={pending}
    >
      <div className="modal-body">
        {conflictError ? (
          <div
            className="plugin-config-conflict-banner"
            role="alert"
            data-testid="plugin-config-conflict-banner"
          >
            {t('plugins.configDialog.conflict')}
          </div>
        ) : null}

        {saveError ? (
          <div className="form-error-banner" role="alert">
            {saveError}
          </div>
        ) : null}

        {!schemaResult.ok ? (
          <div className="plugin-config-warning-banner" role="alert">
            {t('plugins.configDialog.unsupportedSchema', {
              error: schemaResult.error,
            })}
          </div>
        ) : null}

        {readingFiles ? (
          <div className="plugin-config-warning-banner" role="status">
            {t('plugins.configDialog.fileReadPending')}
          </div>
        ) : null}

        {valuesTooLarge ? (
          <div className="plugin-config-warning-banner" role="alert">
            {t('plugins.configDialog.valuesTooLarge', {
              max: MAX_PLUGIN_VALUES_UTF8_BYTES,
            })}
          </div>
        ) : null}

        {/* 模式切换按钮：表单里还有未解决的草稿错误时不允许离开表单模式，避免旧合法值被当成当前配置提交。 */}
        {schemaResult.ok ? (
          <div className="plugin-config-mode-switch">
            <Button
              variant="ghost"
              size="compact"
              disabled={pending || formModeBlockedByDrafts}
              aria-describedby={formModeBlockedByDrafts ? 'plugin-config-mode-blocked' : undefined}
              onClick={() => {
                if (editorMode === 'form' && formModeBlockedByDrafts) {
                  return
                }
                setEditorMode((prev) => (prev === 'form' ? 'json' : 'form'))
              }}
            >
              {editorMode === 'form' ? (
                <>
                  <Code size={14} aria-hidden="true" />
                  {t('plugins.configDialog.jsonEditor')}
                </>
              ) : (
                <>
                  <FormInput size={14} aria-hidden="true" />
                  {t('plugins.configDialog.formEditor')}
                </>
              )}
            </Button>
            {formModeBlockedByDrafts ? (
              <span id="plugin-config-mode-blocked" className="inline-hint danger">
                {t('plugins.configDialog.resolveFormDraftErrors')}
              </span>
            ) : null}
          </div>
        ) : null}

        {/* 普通配置区 */}
        <section className="plugin-config-section">
          <div className="plugin-config-section-title">
            <span>{t('plugins.configDialog.generalSettings')}</span>
             <Badge tone="neutral">{t('plugins.configDialog.configurationVersion', { version })}</Badge>
          </div>

          {editorMode === 'form' && schemaResult.ok && parseResult.ok ? (
            <div className="plugin-config-field-group">
              {Object.entries(schemaResult.schema.properties).map(
                ([propName, element]) => (
                  <SchemaElementRenderer
                    key={propName}
                    name={propName}
                    path={[propName]}
                    element={element}
                    required={schemaResult.schema.required.includes(propName)}
                    rootTree={parseResult.tree}
                    rawJson={valuesJson}
                    disabled={pending}
                    drafts={fieldDrafts}
                    errors={fieldErrors}
                    structureGeneration={structureGeneration}
                    editor={editor}
                  />
                ),
              )}
            </div>
          ) : (
            <div className="form-group">
              <TextArea
                className="plugin-config-json-textarea"
                aria-label={t('plugins.configDialog.jsonEditor')}
                disabled={pending}
                value={valuesJson}
                onChange={(e) => replaceValuesJson(e.target.value)}
                rows={10}
              />
              {!parseResult.ok ? (
                <span className="inline-hint danger" role="alert">
                  {t('plugins.configDialog.jsonSyntaxError', {
                    error: parseResult.error,
                  })}
                </span>
              ) : null}
            </div>
          )}
        </section>

        {/* 秘密配置区 */}
        {hasSecrets ? (
          <section className="plugin-config-section">
            <div className="plugin-config-section-title">
              <span>
                <Lock size={14} aria-hidden="true" style={{ marginRight: 6 }} />
                {t('plugins.configDialog.secrets')}
              </span>
            </div>

            <div className="plugin-config-field-group">
              {Object.entries(plugin.configuration.secrets).map(
                ([secretField, projection]) => {
                  const operation = secretOperations[secretField] || 'KEEP'
                  const isConfigured = projection.configured

                  return (
                    <div
                      key={secretField}
                      className="plugin-config-secret-item"
                      data-testid={`secret-item-${secretField}`}
                    >
                      <div className="plugin-config-secret-header">
                        <div>
                          <div className="plugin-config-secret-name">
                            {secretField}
                          </div>
                          {projection.description ? (
                            <div className="plugin-config-secret-desc">
                              {projection.description}
                            </div>
                          ) : null}
                        </div>

                        <div className="plugin-config-secret-actions">
                          <Badge tone={isConfigured ? 'success' : 'neutral'}>
                            {isConfigured
                              ? t('plugins.secrets.configured')
                              : t('plugins.secrets.notConfigured')}
                          </Badge>

                          {isDeepLink ? (
                            <span className="inline-hint">
                              {t('plugins.configDialog.deepLinkSecretHint')}
                            </span>
                          ) : (
                            <Select
                              value={operation}
                              disabled={pending}
                              aria-label={`Operation for ${secretField}`}
                              options={[
                                {
                                  value: 'KEEP',
                                  label: t('plugins.secrets.keep'),
                                },
                                {
                                  value: 'SET',
                                  label: t('plugins.secrets.set'),
                                },
                                {
                                  value: 'CLEAR',
                                  label: t('plugins.secrets.clear'),
                                },
                              ]}
                              onChange={(next) => {
                                changeOperation(secretField, next as SecretUpdateOperation)
                                setSaveError(null)
                              }}
                            />
                          )}
                        </div>
                      </div>

                      {!isDeepLink && operation === 'SET' ? (
                        <div
                          className="form-group"
                          style={{ marginTop: 8 }}
                        >
                          <FieldLabel required>{t('plugins.secrets.set')}</FieldLabel>
                          <TextInput
                            type="password"
                            autoComplete="off"
                            disabled={pending}
                            aria-label={`New secret for ${secretField}`}
                            placeholder={t('plugins.secrets.inputPlaceholder')}
                            value={Object.hasOwn(secretValues, secretField) ? secretValues[secretField] : ''}
                            onChange={(e) => {
                              // 保持原值，绝不 trim
                              changeSecretValue(secretField, e.target.value)
                              setSaveError(null)
                            }}
                          />
                        </div>
                      ) : null}

                      {!isDeepLink && operation === 'CLEAR' ? (
                        <div
                          className="form-group"
                          style={{ marginTop: 8 }}
                        >
                          <Checkbox
                            id={`confirm-clear-${secretField}`}
                            checked={Object.hasOwn(clearConfirmed, secretField) && clearConfirmed[secretField] === true}
                            disabled={pending}
                            onChange={(checked) => {
                              changeClearConfirmed(secretField, checked)
                              setSaveError(null)
                            }}
                            label={t('plugins.secrets.clearConfirmPrompt')}
                          />
                        </div>
                      ) : null}
                    </div>
                  )
                },
              )}
            </div>
          </section>
        ) : null}

      </div>
      <div className="modal-footer">
        <Button
          type="button"
          variant="ghost"
          disabled={pending}
          onClick={handleClose}
        >
          {t('plugins.configDialog.cancel')}
        </Button>
        <Button
          type="button"
          variant="primary"
          disabled={
            pending ||
            readingFiles ||
            hasFieldErrors ||
            !parseResult.ok ||
            valuesTooLarge ||
            Object.entries(secretOperations).some(([k, op]) =>
              op === 'CLEAR' ? !Object.hasOwn(clearConfirmed, k) || clearConfirmed[k] !== true : false,
            )
          }
          onClick={handleSave}
        >
          {pending
            ? t('plugins.configDialog.saving')
            : t('plugins.configDialog.save')}
        </Button>
      </div>
    </Dialog>
  )
}
