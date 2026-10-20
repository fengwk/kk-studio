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
  appendStrictJsonArrayItem,
  isValidJsonIntegerLiteral,
  isValidJsonNumberLiteral,
  MAX_PLUGIN_VALUES_UTF8_BYTES,
  parseStrictJsonContainer,
  parseStrictJsonObject,
  removeStrictJsonNode,
  updateStrictJsonField,
  utf8ByteLength,
} from './plugin-config-json'
import { isDraftKeyUnderPath, withoutStaleDrafts } from './plugin-draft-keys'
import { parsePluginValuesSchema } from './plugin-schema'
import { SchemaElementRenderer, type PluginSchemaEditor } from './SchemaElementRenderer'
import type { JSONPath } from 'jsonc-parser'
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
  // 结构增删代数：让数组条目子树重建，避免旧条目的局部编辑状态与在途文件读取落到移位后的条目上
  const [structureGeneration, setStructureGeneration] = useState(0)
  // 在途的本地文件读取数量：父组件据此阻止提交，晚到的读取结果因此不可能落在已提交的旧值之后。
  const [pendingFileReads, setPendingFileReads] = useState(0)
  const readingFiles = pendingFileReads > 0

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

  // POST 前的 UTF-8 字节预算：判定对象就是即将提交的 valuesJson 原文（含嵌入字符串的转义开销）。
  const valuesByteLength = useMemo(() => utf8ByteLength(valuesJson), [valuesJson])
  const valuesTooLarge = valuesByteLength > MAX_PLUGIN_VALUES_UTF8_BYTES
  // 表单子树里的草稿错误（键即字段路径）都会拦住离开表单模式与提交，绝不替换成上次的合法值。
  const formModeBlockedByDrafts =
    editorMode === 'form' && Object.keys(fieldErrors).some((key) => isDraftKeyUnderPath(key, []))

  async function handleSave() {
    if (pending || busyRef.current || readingFiles) return

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

    if (valuesTooLarge) {
      setSaveError(
        t('plugins.configDialog.valuesTooLarge', { max: MAX_PLUGIN_VALUES_UTF8_BYTES }),
      )
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

  /** 显式省略可选字段：只有这条路径会删除键，清空输入永远是写入空串。 */
  function handleFieldRemove(path: JSONPath) {
    const fieldKey = JSON.stringify(path)
    setFieldDrafts((prev) => withoutKey(prev, fieldKey))
    setFieldErrors((prev) => withoutKey(prev, fieldKey))
    const res = removeStrictJsonNode(valuesJson, path)
    if (res.ok) {
      setValuesJson(res.newJson)
      setSaveError(null)
    } else {
      setSaveError(res.error)
    }
  }

  /** 字符串字段落值：开启 JSON 校验时先按严格 JSON 判定，非法草稿只报错、绝不写入旧值顶替。 */
  function handleStringEdit(
    path: JSONPath,
    fieldKey: string,
    text: string,
    validateJson: boolean,
  ) {
    if (validateJson) {
      const strict = parseStrictJsonContainer(text)
      if (!strict.ok) {
        setFieldErrors((prev) => ({
          ...prev,
          [fieldKey]: t('plugins.configDialog.invalidJsonText', { error: strict.error }),
        }))
        setSaveError(null)
        return
      }
    }

    setFieldErrors((prev) => withoutKey(prev, fieldKey))
    const res = updateStrictJsonField({ rawJson: valuesJson, path, value: text })
    if (res.ok) {
      setValuesJson(res.newJson)
      setSaveError(null)
    } else {
      setFieldErrors((prev) => ({ ...prev, [fieldKey]: res.error }))
    }
  }

  function handleFieldDraftDiscard(fieldKey: string) {
    setFieldDrafts((prev) => withoutKey(prev, fieldKey))
    setFieldErrors((prev) => withoutKey(prev, fieldKey))
  }

  /** 追加只插入新条目切片：既有条目与草稿都不会移动，因此不重置任何草稿。 */
  function handleArrayItemAppend(path: JSONPath, value: unknown) {
    const res = appendStrictJsonArrayItem({ rawJson: valuesJson, path, value })
    if (!res.ok) {
      setSaveError(res.error)
      return
    }
    setValuesJson(res.newJson)
    setSaveError(null)
  }

  /**
   * 删除条目：只丢弃随目标索引之后条目一起移位的草稿，其余字段草稿原样保留；被移动的条目子树按代数重建。
   */
  function handleArrayItemRemove(path: JSONPath, index: number) {
    const res = removeStrictJsonNode(valuesJson, [...path, index])
    if (!res.ok) {
      setSaveError(res.error)
      return
    }
    setValuesJson(res.newJson)
    setFieldDrafts((prev) => withoutStaleDrafts(prev, path, index))
    setFieldErrors((prev) => withoutStaleDrafts(prev, path, index))
    setStructureGeneration((prev) => prev + 1)
    setSaveError(null)
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

  /** 每次文件读取开始/结束各报告一次，父组件据此持有在途读取计数。 */
  function handleFileReadStart() {
    setPendingFileReads((prev) => prev + 1)
  }

  function handleFileReadEnd() {
    setPendingFileReads((prev) => Math.max(0, prev - 1))
  }

  const editor: PluginSchemaEditor = {
    onFileReadStart: handleFileReadStart,
    onFileReadEnd: handleFileReadEnd,
    onScalarChange: handleFieldScalarChange,
    onFieldRemove: handleFieldRemove,
    onStringEdit: handleStringEdit,
    onFieldDraftDiscard: handleFieldDraftDiscard,
    onNumberDraftChange: handleFieldDraftNumberChange,
    onRawSnippetChange: handleFieldRawSnippetChange,
    onArrayItemAppend: handleArrayItemAppend,
    onArrayItemRemove: handleArrayItemRemove,
  }

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
            <Badge tone="neutral">
              Rev {plugin.configuration.revision ?? ''}
            </Badge>
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
            readingFiles ||
            !hasRevision ||
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

/** 复制并去掉指定键；草稿与错误映射共用同一不可变更新方式。 */
function withoutKey<T>(record: Record<string, T>, key: string): Record<string, T> {
  const next = { ...record }
  delete next[key]
  return next
}
