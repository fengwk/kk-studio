import { useId, useLayoutEffect, useMemo, useRef, useState } from 'react'
import { useMutation } from '@tanstack/react-query'
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
  parseStrictJsonObject,
  updateStrictJsonField,
} from './plugin-config-json'
import {
  parsePluginValuesSchema,
  type PluginSchemaElement,
} from './plugin-schema'
import { findNodeAtLocation, type JSONPath } from 'jsonc-parser'
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
  const [valuesJson, setValuesJson] = useState(plugin.configuration.valuesJson)
  const [secretOperations, setSecretOperations] = useState<
    Record<string, PluginSecretPatchOperation>
  >(() => {
    const initial: Record<string, PluginSecretPatchOperation> = {}
    for (const key of Object.keys(plugin.configuration.secrets)) {
      initial[key] = 'KEEP'
    }
    return initial
  })
  const [secretValues, setSecretValues] = useState<Record<string, string>>({})
  const [clearConfirmed, setClearConfirmed] = useState<Record<string, boolean>>({})

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
      // 卸载时彻底清空内存中的秘密值
      setSecretValues({})
    }
  }, [])

  // 严格验证 valuesJson
  const parseResult = useMemo(
    () => parseStrictJsonObject(valuesJson),
    [valuesJson],
  )

  const updateMutation = useMutation({
    mutationFn: (request: PluginConfigurationUpdateRequestDTO) =>
      pluginsService.updateConfiguration(plugin.pluginId, request),
    retry: false,
    onSuccess: (updated) => {
      if (!mountedRef.current) return
      setSecretValues({})
      onSuccess(updated)
    },
    onError: (err: unknown) => {
      if (!mountedRef.current) return
      if (isConflictError(err)) {
        setConflictError(true)
      } else {
        setSaveError(err instanceof Error ? err.message : String(err))
      }
    },
    onSettled: () => {
      busyRef.current = false
    },
  })

  const pending = updateMutation.isPending

  function handleSave() {
    if (pending || busyRef.current) return
    if (!parseResult.ok) {
      setSaveError(parseResult.error ?? 'Invalid JSON')
      return
    }

    // 组装 secretPatch
    const secretPatch: Record<string, PluginSecretPatchDTO> = {}
    for (const [key, op] of Object.entries(secretOperations)) {
      if (op === 'KEEP') {
        secretPatch[key] = { operation: 'KEEP' }
      } else if (op === 'CLEAR') {
        secretPatch[key] = { operation: 'CLEAR' }
      } else if (op === 'SET') {
        const val = secretValues[key]?.trim()
        if (!val) {
          setSaveError(`Secret "${key}" requires a non-empty value`)
          return
        }
        secretPatch[key] = { operation: 'SET', value: val }
      }
    }

    const payload: PluginConfigurationUpdateRequestDTO = {
      expectedEpoch: plugin.desiredEpoch,
      expectedRevision: plugin.configuration.revision ?? '0',
      valuesJson,
      secretPatch: Object.keys(secretPatch).length > 0 ? secretPatch : undefined,
    }

    busyRef.current = true
    setSaveError(null)
    setConflictError(false)
    updateMutation.mutate(payload)
  }

  function handleClose() {
    if (!pending && !busyRef.current) {
      setSecretValues({})
      onClose()
    }
  }

  function handleFieldScalarChange(
    path: JSONPath,
    value: unknown,
    rawNumberString?: string,
  ) {
    const res = updateStrictJsonField({
      rawJson: valuesJson,
      path,
      value,
      rawNumberString,
    })
    if (res.ok && res.newJson !== undefined) {
      setValuesJson(res.newJson)
    } else if (!res.ok && res.error) {
      setSaveError(res.error)
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
              Rev {plugin.configuration.revision ?? '0'}
            </span>
          </div>

          {editorMode === 'form' && schemaResult.ok && parseResult.tree ? (
            <div className="plugin-config-field-group">
              {Object.entries(schemaResult.schema.properties).map(
                ([propName, element]) => (
                  <SchemaElementRenderer
                    key={propName}
                    name={propName}
                    path={[propName]}
                    element={element}
                    rootTree={parseResult.tree!}
                    rawJson={valuesJson}
                    disabled={pending}
                    onChange={handleFieldScalarChange}
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
                  setSaveError(null)
                }}
                rows={10}
              />
              {!parseResult.ok ? (
                <span className="inline-hint danger" role="alert">
                  {t('plugins.configDialog.jsonSyntaxError', {
                    error: parseResult.error ?? '',
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
                          <div className="plugin-config-secret-desc">
                            {projection.description}
                          </div>
                        </div>
                        <div className="plugin-config-secret-actions">
                          <span
                            className={`status-pill ${
                              isConfigured ? 'is-ready' : 'is-offline'
                            }`}
                          >
                            {isConfigured
                              ? t('plugins.secrets.configured')
                              : t('plugins.secrets.notConfigured')}
                          </span>
                          <Select
                            aria-label={`Operation for ${secretField}`}
                            value={operation}
                            disabled={pending}
                            options={[
                              { value: 'KEEP', label: t('plugins.secrets.keep') },
                              { value: 'SET', label: t('plugins.secrets.set') },
                              ...(isConfigured
                                ? [
                                    {
                                      value: 'CLEAR',
                                      label: t('plugins.secrets.clear'),
                                    },
                                  ]
                                : []),
                            ]}
                            onChange={(val) => {
                              const nextOp = val as PluginSecretPatchOperation
                              setSecretOperations((prev) => ({
                                ...prev,
                                [secretField]: nextOp,
                              }))
                              if (nextOp !== 'SET') {
                                setSecretValues((prev) => {
                                  const copy = { ...prev }
                                  delete copy[secretField]
                                  return copy
                                })
                              }
                              if (nextOp !== 'CLEAR') {
                                setClearConfirmed((prev) => ({
                                  ...prev,
                                  [secretField]: false,
                                }))
                              }
                            }}
                          />
                        </div>
                      </div>

                      {operation === 'SET' ? (
                        <div className="plugin-config-secret-input">
                          <TextInput
                            type="password"
                            autoComplete="off"
                            placeholder={t('plugins.secrets.inputPlaceholder')}
                            aria-label={`New secret for ${secretField}`}
                            disabled={pending}
                            value={secretValues[secretField] || ''}
                            onChange={(e) => {
                              const v = e.target.value
                              setSecretValues((prev) => ({
                                ...prev,
                                [secretField]: v,
                              }))
                            }}
                            required
                          />
                        </div>
                      ) : null}

                      {operation === 'CLEAR' ? (
                        <div className="plugin-config-secret-input">
                          <Checkbox
                            checked={clearConfirmed[secretField] || false}
                            disabled={pending}
                            onChange={(checked) =>
                              setClearConfirmed((prev) => ({
                                ...prev,
                                [secretField]: checked,
                              }))
                            }
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

        <div className="modal-footer plugin-connect-footer">
          <Button variant="inline" onClick={handleClose} disabled={pending}>
            {t('plugins.configDialog.cancel')}
          </Button>
          <Button
            type="button"
            disabled={
              pending ||
              !parseResult.ok ||
              Object.entries(secretOperations).some(
                ([field, op]) => op === 'CLEAR' && !clearConfirmed[field],
              ) ||
              Object.entries(secretOperations).some(
                ([field, op]) => op === 'SET' && !secretValues[field]?.trim(),
              )
            }
            onClick={handleSave}
          >
            {pending
              ? t('plugins.configDialog.saving')
              : t('plugins.configDialog.save')}
          </Button>
        </div>
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
  onChange,
}: {
  name: string
  path: JSONPath
  element: PluginSchemaElement
  rootTree: import('jsonc-parser').Node
  rawJson: string
  disabled: boolean
  onChange: (path: JSONPath, value: unknown, rawNumberString?: string) => void
}) {
  const node = findNodeAtLocation(rootTree, path)
  const currentSafeVal = node ? getNodeSafeValue(node, rawJson) : undefined
  const fieldId = useId()

  switch (element.kind) {
    case 'boolean': {
      const isChecked = typeof currentSafeVal === 'boolean' ? currentSafeVal : false
      return (
        <div className="form-group" key={name}>
          <Checkbox
            id={fieldId}
            checked={isChecked}
            disabled={disabled}
            onChange={(checked) => onChange(path, checked)}
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
        const val = typeof currentSafeVal === 'string' ? currentSafeVal : element.enum[0]
        return (
          <div className="form-group" key={name}>
            <FieldLabel>{name}</FieldLabel>
            <Select
              id={fieldId}
              value={val}
              disabled={disabled}
              options={element.enum.map((opt) => ({ value: opt, label: opt }))}
              onChange={(next) => onChange(path, next)}
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
            onChange={(e) => onChange(path, e.target.value)}
          />
          {element.description ? (
            <span className="inline-hint">{element.description}</span>
          ) : null}
        </div>
      )
    }

    case 'integer':
    case 'number': {
      // 保持原 token 字符串
      const valStr =
        typeof currentSafeVal === 'string'
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
            onChange={(e) => {
              const text = e.target.value
              onChange(path, undefined, text)
            }}
          />
          {element.description ? (
            <span className="inline-hint">{element.description}</span>
          ) : null}
        </div>
      )
    }

    case 'object': {
      return (
        <fieldset className="plugin-config-fieldset" key={name}>
          <legend>{name}</legend>
          {element.description ? (
            <p className="inline-hint" style={{ marginTop: 0 }}>
              {element.description}
            </p>
          ) : null}
          <div className="plugin-config-field-group">
            {Object.entries(element.properties).map(([subKey, subEl]) => (
              <SchemaElementRenderer
                key={subKey}
                name={subKey}
                path={[...path, subKey]}
                element={subEl}
                rootTree={rootTree}
                rawJson={rawJson}
                disabled={disabled}
                onChange={onChange}
              />
            ))}
          </div>
        </fieldset>
      )
    }

    case 'array': {
      // 简单集合渲染为只读提示或文本区域
      const valDisplay = node ? rawJson.slice(node.offset, node.offset + node.length) : '[]'
      return (
        <div className="form-group" key={name}>
          <FieldLabel>{name}</FieldLabel>
          <TextInput
            id={fieldId}
            value={valDisplay}
            disabled={true}
          />
          {element.description ? (
            <span className="inline-hint">{element.description}</span>
          ) : null}
        </div>
      )
    }
  }
}
