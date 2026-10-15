import { useId, useLayoutEffect, useMemo, useRef, useState } from 'react'
import { Code, FormInput, Lock } from 'lucide-react'
import { Button } from '@/shared/ui/controls/Button'
import { Checkbox } from '@/shared/ui/controls/Checkbox'
import { FieldLabel } from '@/shared/ui/controls/FieldLabel'
import { Select } from '@/shared/ui/controls/Select'
import { TextArea } from '@/shared/ui/controls/TextArea'
import { TextInput } from '@/shared/ui/controls/TextInput'
import { Dialog } from '@/shared/ui/overlays/Dialog'
import { pluginsService } from '@/shared/api/plugins-service'
import { isConflictError } from '@/shared/api/client'
import { useI18n } from '@/shared/i18n'
import type {
  PluginConfigurationUpdateRequestDTO,
  PluginDTO,
  PluginSecretPatchDTO,
  PluginSecretPatchOperation,
} from '@/shared/api/contracts/ai-plugin'
import {
  getNodeSafeValue,
  getNodeSlice,
  isValidJsonIntegerLiteral,
  isValidJsonNumberLiteral,
  parseStrictJsonContainer,
  parseStrictJsonObject,
  updateStrictJsonField,
} from './plugin-config-json'
import {
  parsePluginValuesSchema,
  type PluginSchemaElement,
} from './plugin-schema'
import { findNodeAtLocation, type JSONPath, type Node } from 'jsonc-parser'
import './plugin-config.css'

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
  const hasRevision = Boolean(plugin.configuration.revision)

  const [valuesJson, setValuesJson] = useState(plugin.configuration.valuesJson)
  const [secretOperations, setSecretOperations] = useState<
    Record<string, PluginSecretPatchOperation>
  >(() => {
    const initial: Record<string, PluginSecretPatchOperation> = Object.create(null)
    for (const key of Object.keys(plugin.configuration.secrets)) {
      initial[key] = 'KEEP'
    }
    return initial
  })
  const [secretValues, setSecretValues] = useState<Record<string, string>>({})
  const [clearConfirmed, setClearConfirmed] = useState<Record<string, boolean>>({})

  // 数字与复杂容器的局部草稿及草稿错误状态
  const [fieldDrafts, setFieldDrafts] = useState<Record<string, string>>({})
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({})

  const [pending, setPending] = useState(false)
  const [conflictError, setConflictError] = useState(false)
  const [saveError, setSaveError] = useState<string | null>(null)
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
      // 卸载时彻底清空内存中的秘密值，不进入全局缓存
      setSecretValues({})
    }
  }, [])

  // 严格验证 valuesJson
  const parseResult = useMemo(
    () => parseStrictJsonObject(valuesJson),
    [valuesJson],
  )

  const hasFieldErrors = Object.keys(fieldErrors).length > 0

  async function handleSave() {
    if (pending || busyRef.current) return

    if (!hasRevision) {
      setSaveError(t('plugins.configDialog.missingRevision'))
      return
    }

    if (hasFieldErrors) {
      const firstErr = Object.values(fieldErrors)[0]
      setSaveError(firstErr)
      return
    }

    if (!parseResult.ok) {
      setSaveError(parseResult.error)
      return
    }

    // 组装 secretPatch
    const secretPatch: Record<string, PluginSecretPatchDTO> = Object.create(null)
    for (const [key, op] of Object.entries(secretOperations)) {
      if (isDeepLink || op === 'KEEP') {
        secretPatch[key] = { operation: 'KEEP' }
      } else if (op === 'CLEAR') {
        // 在提交 handler 中严格检查确认勾选，不仅依靠 disabled
        if (!Object.hasOwn(clearConfirmed, key) || clearConfirmed[key] !== true) {
          setSaveError(t('plugins.configDialog.mustConfirmClear', { key }))
          return
        }
        secretPatch[key] = { operation: 'CLEAR' }
      } else if (op === 'SET') {
        // SET 严格保留用户原值，严禁静默 trim，无 padding
        const val = Object.hasOwn(secretValues, key) ? secretValues[key] : undefined
        if (val === undefined || val === '') {
          setSaveError(t('plugins.configDialog.secretRequired', { key }))
          return
        }
        secretPatch[key] = { operation: 'SET', value: val }
      }
    }

    const payload: PluginConfigurationUpdateRequestDTO = {
      expectedEpoch: plugin.desiredEpoch,
      expectedRevision: plugin.configuration.revision!,
      valuesJson,
      secretPatch: Object.keys(secretPatch).length > 0 ? secretPatch : undefined,
    }

    busyRef.current = true
    setSecretValues({})
    setPending(true)
    setSaveError(null)
    setConflictError(false)

    try {
      const updated = await pluginsService.updateConfiguration(plugin.pluginId, payload)
      if (!mountedRef.current) return
      setSecretValues({})
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
      setSecretValues({})
      onClose()
    }
  }

  function handleFieldScalarChange(path: JSONPath, value: unknown) {
    const res = updateStrictJsonField({
      rawJson: valuesJson,
      path,
      value,
    })
    if (res.ok) {
      setValuesJson(res.newJson)
    } else {
      setSaveError(res.error)
    }
  }

  function handleFieldDraftNumberChange(
    path: JSONPath,
    fieldKey: string,
    rawText: string,
    isInteger: boolean,
  ) {
    setFieldDrafts((prev) => ({ ...prev, [fieldKey]: rawText }))

    const isValid = isInteger
      ? isValidJsonIntegerLiteral(rawText)
      : isValidJsonNumberLiteral(rawText)

    if (!isValid) {
      setFieldErrors((prev) => ({
        ...prev,
        [fieldKey]: isInteger
          ? t('plugins.configDialog.invalidInteger')
          : t('plugins.configDialog.invalidNumber'),
      }))
      return
    }

    // 草稿合法，清除错误并更新 valuesJson
    setFieldErrors((prev) => {
      const next = { ...prev }
      delete next[fieldKey]
      return next
    })

    const res = updateStrictJsonField({
      rawJson: valuesJson,
      path,
      rawNumberString: rawText,
    })
    if (res.ok) {
      setValuesJson(res.newJson)
    } else {
      setFieldErrors((prev) => ({ ...prev, [fieldKey]: res.error }))
    }
  }

  function handleFieldRawSnippetChange(
    path: JSONPath,
    fieldKey: string,
    snippetText: string,
    expectedType: 'array' | 'object',
  ) {
    setFieldDrafts((prev) => ({ ...prev, [fieldKey]: snippetText }))

    const strict = parseStrictJsonContainer(snippetText, expectedType)
    if (!strict.ok) {
      setFieldErrors((prev) => ({
        ...prev,
        [fieldKey]: t('plugins.configDialog.invalidContainerJson', {
          error: strict.error,
        }),
      }))
      return
    }

    setFieldErrors((prev) => {
      const next = { ...prev }
      delete next[fieldKey]
      return next
    })

    const res = updateStrictJsonField({
      rawJson: valuesJson,
      path,
      rawSnippetString: snippetText,
    })
    if (res.ok) {
      setValuesJson(res.newJson)
    } else {
      setFieldErrors((prev) => ({ ...prev, [fieldKey]: res.error }))
    }
  }

  const hasSecrets = Object.keys(plugin.configuration.secrets).length > 0

  return (
    <Dialog
      className="resource-modal-card plugin-config-modal"
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

        {!hasRevision ? (
          <div className="form-error-banner" role="alert">
            {t('plugins.configDialog.missingRevision')}
          </div>
        ) : null}

        {!schemaResult.ok ? (
          <div className="plugin-config-warning-banner" role="alert">
            {t('plugins.configDialog.unsupportedSchema', {
              error: schemaResult.error,
            })}
          </div>
        ) : null}

        {/* 模式切换按钮 */}
        {schemaResult.ok ? (
          <div className="plugin-config-mode-switch">
            <Button
              variant="ghost"
              size="compact"
              disabled={pending}
              onClick={() =>
                setEditorMode((prev) => (prev === 'form' ? 'json' : 'form'))
              }
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
          </div>
        ) : null}

        {/* 普通配置区 */}
        <section className="plugin-config-section">
          <div className="plugin-config-section-title">
            <span>{t('plugins.configDialog.generalSettings')}</span>
            <span className="status-pill is-neutral">
              Rev {plugin.configuration.revision ?? ''}
            </span>
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
                    rootTree={parseResult.tree}
                    rawJson={valuesJson}
                    disabled={pending}
                    drafts={fieldDrafts}
                    errors={fieldErrors}
                    onScalarChange={handleFieldScalarChange}
                    onNumberDraftChange={handleFieldDraftNumberChange}
                    onRawSnippetChange={handleFieldRawSnippetChange}
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
                onChange={(e) => {
                  setValuesJson(e.target.value)
                  setFieldDrafts({})
                  setFieldErrors({})
                  setSaveError(null)
                }}
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
                          <span
                            className={`status-pill ${
                              isConfigured ? 'is-ready' : 'is-neutral'
                            }`}
                          >
                            {isConfigured
                              ? t('plugins.secrets.configured')
                              : t('plugins.secrets.notConfigured')}
                          </span>

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
                                setSecretOperations((prev) => ({
                                  ...prev,
                                  [secretField]: next as PluginSecretPatchOperation,
                                }))
                                setSecretValues((prev) => {
                                  const remaining = { ...prev }
                                  delete remaining[secretField]
                                  return remaining
                                })
                                setClearConfirmed((prev) => {
                                  const remaining = { ...prev }
                                  delete remaining[secretField]
                                  return remaining
                                })
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
                              const nextVal = e.target.value
                              setSecretValues((prev) => ({
                                ...prev,
                                [secretField]: nextVal,
                              }))
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
                              setClearConfirmed((prev) => ({
                                ...prev,
                                [secretField]: checked,
                              }))
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
            !hasRevision ||
            hasFieldErrors ||
            !parseResult.ok ||
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

function SchemaElementRenderer({
  name,
  path,
  element,
  rootTree,
  rawJson,
  disabled,
  drafts,
  errors,
  onScalarChange,
  onNumberDraftChange,
  onRawSnippetChange,
}: {
  name: string
  path: JSONPath
  element: PluginSchemaElement
  rootTree: Node
  rawJson: string
  disabled: boolean
  drafts: Record<string, string>
  errors: Record<string, string>
  onScalarChange: (path: JSONPath, value: unknown) => void
  onNumberDraftChange: (
    path: JSONPath,
    fieldKey: string,
    rawText: string,
    isInteger: boolean,
  ) => void
  onRawSnippetChange: (
    path: JSONPath,
    fieldKey: string,
    snippetText: string,
    expectedType: 'array' | 'object',
  ) => void
}) {
  const node = findNodeAtLocation(rootTree, path)
  const currentSafeVal = node ? getNodeSafeValue(node, rawJson) : undefined
  const fieldId = useId()
  const fieldKey = JSON.stringify(path)
  const fieldError = errors[fieldKey]

  switch (element.kind) {
    case 'boolean': {
      const isChecked = typeof currentSafeVal === 'boolean' ? currentSafeVal : false
      return (
        <div className="form-group" key={name}>
          <Checkbox
            id={fieldId}
            checked={isChecked}
            disabled={disabled}
            onChange={(checked) => onScalarChange(path, checked)}
            label={name}
          />
          {element.description ? (
            <span className="inline-hint">{element.description}</span>
          ) : null}
        </div>
      )
    }

    case 'string': {
      if (element.enum && element.enum.length > 0) {
        const val = typeof currentSafeVal === 'string' ? currentSafeVal : ''
        return (
          <div className="form-group" key={name}>
            <FieldLabel>{name}</FieldLabel>
            <Select
              id={fieldId}
              value={val}
              disabled={disabled}
              aria-label={name}
              options={element.enum.map((opt) => ({ value: opt, label: opt }))}
              onChange={(next) => onScalarChange(path, next)}
            />
            {element.description ? (
              <span className="inline-hint">{element.description}</span>
            ) : null}
          </div>
        )
      }

      const val = typeof currentSafeVal === 'string' ? currentSafeVal : ''
      return (
        <div className="form-group" key={name}>
          <FieldLabel>{name}</FieldLabel>
          <TextInput
            id={fieldId}
            value={val}
            disabled={disabled}
            aria-label={name}
            onChange={(e) => onScalarChange(path, e.target.value)}
          />
          {element.description ? (
            <span className="inline-hint">{element.description}</span>
          ) : null}
        </div>
      )
    }

    case 'integer':
    case 'number': {
      const isInteger = element.kind === 'integer'
      const valStr =
        drafts[fieldKey] !== undefined
          ? drafts[fieldKey]
          : typeof currentSafeVal === 'string'
            ? currentSafeVal
            : typeof currentSafeVal === 'number'
              ? String(currentSafeVal)
              : ''

      return (
        <div className="form-group" key={name}>
          <FieldLabel>{name}</FieldLabel>
          <TextInput
            id={fieldId}
            value={valStr}
            disabled={disabled}
            aria-label={name}
            onChange={(e) => {
              onNumberDraftChange(path, fieldKey, e.target.value, isInteger)
            }}
          />
          {element.description ? (
            <span className="inline-hint">{element.description}</span>
          ) : null}
          {fieldError ? (
            <span className="inline-hint danger" role="alert">
              {fieldError}
            </span>
          ) : null}
        </div>
      )
    }

    case 'object': {
      const hasSpecificProperties = Object.keys(element.properties).length > 0
      // 若包含具体 properties 且不允许任意额外属性，渲染嵌套字段
      if (hasSpecificProperties && !element.additionalProperties) {
        return (
          <fieldset key={name} className="plugin-config-fieldset">
            <legend>{name}</legend>
            {element.description ? (
              <p className="inline-hint" style={{ marginTop: 0 }}>
                {element.description}
              </p>
            ) : null}
            <div className="plugin-config-field-group">
              {Object.entries(element.properties).map(([subName, subElement]) => (
                <SchemaElementRenderer
                  key={subName}
                  name={subName}
                  path={[...path, subName]}
                  element={subElement}
                  rootTree={rootTree}
                  rawJson={rawJson}
                  disabled={disabled}
                  drafts={drafts}
                  errors={errors}
                  onScalarChange={onScalarChange}
                  onNumberDraftChange={onNumberDraftChange}
                  onRawSnippetChange={onRawSnippetChange}
                />
              ))}
            </div>
          </fieldset>
        )
      }

      // 动态对象或空 properties 对象：提供局部 Raw JSON 文本框
      const rawSnippet =
        drafts[fieldKey] !== undefined
          ? drafts[fieldKey]
          : node
            ? getNodeSlice(rawJson, node)
            : '{}'

      return (
        <div className="form-group" key={name}>
          <FieldLabel>{name}</FieldLabel>
          <TextArea
            id={fieldId}
            value={rawSnippet}
            disabled={disabled}
            aria-label={name}
            rows={4}
            onChange={(e) => {
              onRawSnippetChange(path, fieldKey, e.target.value, 'object')
            }}
          />
          {element.description ? (
            <span className="inline-hint">{element.description}</span>
          ) : null}
          {fieldError ? (
            <span className="inline-hint danger" role="alert">
              {fieldError}
            </span>
          ) : null}
        </div>
      )
    }

    case 'array': {
      // 集合容器：提供局部 Raw JSON 文本框编辑，保持原 tokens
      const rawSnippet =
        drafts[fieldKey] !== undefined
          ? drafts[fieldKey]
          : node
            ? getNodeSlice(rawJson, node)
            : '[]'

      return (
        <div className="form-group" key={name}>
          <FieldLabel>{name}</FieldLabel>
          <TextArea
            id={fieldId}
            value={rawSnippet}
            disabled={disabled}
            aria-label={name}
            rows={3}
            onChange={(e) => {
              onRawSnippetChange(path, fieldKey, e.target.value, 'array')
            }}
          />
          {element.description ? (
            <span className="inline-hint">{element.description}</span>
          ) : null}
          {fieldError ? (
            <span className="inline-hint danger" role="alert">
              {fieldError}
            </span>
          ) : null}
        </div>
      )
    }
  }
}
