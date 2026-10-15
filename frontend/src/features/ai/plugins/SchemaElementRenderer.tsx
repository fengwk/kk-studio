import { useId, useLayoutEffect, useRef, useState, type ChangeEvent, type ReactNode } from 'react'
import { FileUp, Plus, Trash2 } from 'lucide-react'
import { Button } from '@/shared/ui/controls/Button'
import { Checkbox } from '@/shared/ui/controls/Checkbox'
import { FieldLabel } from '@/shared/ui/controls/FieldLabel'
import { Select } from '@/shared/ui/controls/Select'
import { TextArea } from '@/shared/ui/controls/TextArea'
import { TextInput } from '@/shared/ui/controls/TextInput'
import { useI18n } from '@/shared/i18n'
import { findNodeAtLocation, type JSONPath, type Node } from 'jsonc-parser'
import {
  getNodeSafeValue,
  getNodeSlice,
  MAX_PLUGIN_VALUES_UTF8_BYTES,
} from './plugin-config-json'
import { isDraftKeyUnderPath } from './plugin-draft-keys'
import { readStrictJsonFile, type JsonFileReadResult } from './plugin-config-file'
import { buildSchemaPlaceholder, type PluginSchemaElement } from './plugin-schema'

/**
 * 编辑器对外暴露的唯一写入口：所有落值都写回外层同一个 valuesJson 草稿，本组件不持有第二份配置。
 *
 * <p>字符串字段既能以普通文本编辑，也能显式开启 JSON 校验并从本地文件导入；受控 object 数组按 schema 递归展开条目，
 * 增删只改目标切片。
 */
export interface PluginSchemaEditor {
  /** 本地文件读取开始：父组件据此持有在途读取计数并阻止提交。 */
  onFileReadStart: () => void
  /** 本地文件读取结束：与 onFileReadStart 成对调用（同一 finally），不会漏报或重复。 */
  onFileReadEnd: () => void
  /** 写入普通值（boolean / string / enum）。 */
  onScalarChange: (path: JSONPath, value: unknown) => void
  /** 显式省略一个可选字段。 */
  onFieldRemove: (path: JSONPath) => void
  /** 写入字符串字段内容；validateJson 为真时先按严格 JSON 校验。 */
  onStringEdit: (path: JSONPath, fieldKey: string, text: string, validateJson: boolean) => void
  /** 丢弃某字段的局部草稿与错误。 */
  onFieldDraftDiscard: (fieldKey: string) => void
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
  /** 在数组末尾追加一个按 schema 构建的待填写条目。 */
  onArrayItemAppend: (path: JSONPath, value: unknown) => void
  /** 删除数组中的指定条目。 */
  onArrayItemRemove: (path: JSONPath, index: number) => void
}

export interface SchemaElementRendererProps {
  name: string
  path: JSONPath
  element: PluginSchemaElement
  /** 父对象 schema 是否要求该属性：可选字段可以整体省略，但绝不因清空输入而被删除。 */
  required: boolean
  rootTree: Node
  rawJson: string
  disabled: boolean
  drafts: Record<string, string>
  errors: Record<string, string>
  /** 结构删除代数：被移动的条目子树按代数重建，避免旧条目的局部编辑状态落到移位后的条目上。 */
  structureGeneration: number
  editor: PluginSchemaEditor
}

/**
 * Schema 驱动的通用配置字段渲染器。
 *
 * <p>不按 pluginId、字段名或描述特判：对象按 schema 展开嵌套字段，受控 object 数组按 schema 递归展开条目并支持增删，
 * 其余容器保留显式 Raw JSON 编辑；普通字符串可显式打开多行/JSON 编辑器并导入本地 JSON 文件。可选字段的省略只有显式
 * 删除一种途径，因此清空合法空串与「未设置」是两种不同的状态。
 */
export function SchemaElementRenderer({
  name,
  path,
  element,
  required,
  rootTree,
  rawJson,
  disabled,
  drafts,
  errors,
  structureGeneration,
  editor,
}: SchemaElementRendererProps) {
  const { t } = useI18n()
  const node = findNodeAtLocation(rootTree, path)
  const currentSafeVal = node ? getNodeSafeValue(node, rawJson) : undefined
  const fieldId = useId()
  const fieldKey = JSON.stringify(path)
  const fieldError = errors[fieldKey]

  // 局部 UI 状态只在单个字段实例内生效，绝不成为第二份配置。
  const [textEditorOpen, setTextEditorOpen] = useState(false)
  const [validateJsonText, setValidateJsonText] = useState(false)
  const [textDraft, setTextDraft] = useState<string | null>(null)
  const [rawMode, setRawMode] = useState(false)
  const [fileError, setFileError] = useState<string | null>(null)
  const fileInputRef = useRef<HTMLInputElement | null>(null)
  // 在途文件读取令牌：本字段的新编辑、模式切换或卸载都会让晚到的读取结果失效。
  const fileReadTokenRef = useRef(0)

  useLayoutEffect(() => {
    return () => {
      fileReadTokenRef.current += 1
    }
  }, [])

  /** 按父对象 schema 递归渲染属性。 */
  const renderObjectProperties = (
    objectElement: Extract<PluginSchemaElement, { kind: 'object' }>,
    basePath: JSONPath,
  ): ReactNode =>
    Object.entries(objectElement.properties).map(([subName, subElement]) => (
      <SchemaElementRenderer
        key={subName}
        name={subName}
        path={[...basePath, subName]}
        element={subElement}
        required={objectElement.required.includes(subName)}
        rootTree={rootTree}
        rawJson={rawJson}
        disabled={disabled}
        drafts={drafts}
        errors={errors}
        structureGeneration={structureGeneration}
        editor={editor}
      />
    ))

  /**
   * 可选字段的显式省略入口：只有它删除键，文本框清空永远是写入合法空串。
   * 字段当前不存在时没有可省略的状态，因此不渲染。
   */
  const unsetOptionalButton =
    !required && node !== undefined ? (
      <Button
        variant="ghost"
        size="compact"
        disabled={disabled}
        aria-label={t('plugins.configDialog.unsetOptionalField', { name })}
        onClick={() => {
          fileReadTokenRef.current += 1
          setTextDraft(null)
          setFileError(null)
          editor.onFieldRemove(path)
        }}
      >
        {t('plugins.configDialog.unsetOptionalFieldLabel')}
      </Button>
    ) : null

  switch (element.kind) {
    case 'boolean': {
      const isChecked = typeof currentSafeVal === 'boolean' ? currentSafeVal : false
      return (
        <div className="form-group" key={name}>
          <div className="plugin-config-field-header">
            <Checkbox
              id={fieldId}
              checked={isChecked}
              disabled={disabled}
              onChange={(checked) => editor.onScalarChange(path, checked)}
              label={name}
            />
            {unsetOptionalButton}
          </div>
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
              onChange={(next) => editor.onScalarChange(path, next)}
            />
            {element.description ? (
              <span className="inline-hint">{element.description}</span>
            ) : null}
          </div>
        )
      }

      const committedText = typeof currentSafeVal === 'string' ? currentSafeVal : ''
      const textValue = textDraft ?? committedText

      /** 普通文本编辑：清空写入空串，省略只经显式入口。 */
      const applyPlainText = (next: string) => {
        fileReadTokenRef.current += 1
        setFileError(null)
        setTextDraft(null)
        editor.onScalarChange(path, next)
      }

      const applyText = (next: string) => {
        if (!validateJsonText) {
          applyPlainText(next)
          return
        }
        fileReadTokenRef.current += 1
        setFileError(null)
        setTextDraft(next)
        editor.onStringEdit(path, fieldKey, next, true)
      }

      /** 关闭 JSON 校验：草稿文本按普通字符串落值，并清掉不再成立的校验错误。 */
      const toggleJsonValidation = (checked: boolean) => {
        // 校验模式变化会让在途读取的结果不再对应当前模式。
        fileReadTokenRef.current += 1
        setValidateJsonText(checked)
        if (checked) {
          editor.onStringEdit(path, fieldKey, textValue, true)
          return
        }
        editor.onFieldDraftDiscard(fieldKey)
        setTextDraft(null)
        editor.onScalarChange(path, textValue)
      }

      const discardTextDraft = () => {
        fileReadTokenRef.current += 1
        setFileError(null)
        setTextDraft(null)
        editor.onFieldDraftDiscard(fieldKey)
      }

      const onFileSelected = async (event: ChangeEvent<HTMLInputElement>) => {
        const input = event.target
        const file = input.files?.[0]
        // 立刻复位，允许再次选择同一个文件。
        input.value = ''
        if (!file) {
          return
        }
        const token = fileReadTokenRef.current + 1
        fileReadTokenRef.current = token
        setFileError(null)

        let result: JsonFileReadResult
        editor.onFileReadStart()
        try {
          result = await readStrictJsonFile(file)
        } finally {
          editor.onFileReadEnd()
        }
        // 晚到的读取结果绝不覆盖期间发生的新编辑、换模式或让旧值复活。
        if (fileReadTokenRef.current !== token) {
          return
        }
        if (!result.ok) {
          setFileError(fileErrorMessage(result, t))
          return
        }
        if (!validateJsonText) {
          setTextDraft(null)
          editor.onStringEdit(path, fieldKey, result.text, false)
          return
        }
        setTextDraft(result.text)
        editor.onStringEdit(path, fieldKey, result.text, true)
      }

      return (
        <div className="form-group" key={name}>
          <div className="plugin-config-field-header">
            <FieldLabel>{name}</FieldLabel>
            <div className="plugin-config-field-actions">
              {unsetOptionalButton}
              <Button
                variant="ghost"
                size="compact"
                disabled={disabled}
                aria-expanded={textEditorOpen}
                aria-label={t('plugins.configDialog.toggleTextEditor', { name })}
                onClick={() => {
                  // 折叠/展开会卸载或重建编辑器视图，在途读取结果同样失效。
                  fileReadTokenRef.current += 1
                  setFileError(null)
                  setTextEditorOpen((prev) => !prev)
                }}
              >
                {textEditorOpen
                  ? t('plugins.configDialog.collapseTextEditor')
                  : t('plugins.configDialog.expandTextEditor')}
              </Button>
            </div>
          </div>

          {textEditorOpen ? (
            <div className="plugin-config-text-editor">
              <TextArea
                id={fieldId}
                className="plugin-config-text-editor-area"
                aria-label={name}
                value={textValue}
                disabled={disabled}
                invalid={Boolean(fieldError)}
                rows={8}
                onChange={(e) => applyText(e.target.value)}
              />
              <div className="plugin-config-text-editor-toolbar">
                <Checkbox
                  checked={validateJsonText}
                  disabled={disabled}
                  label={t('plugins.configDialog.validateJsonText')}
                  onChange={toggleJsonValidation}
                />
                <Button
                  variant="ghost"
                  size="compact"
                  disabled={disabled}
                  onClick={() => fileInputRef.current?.click()}
                >
                  <FileUp size={14} aria-hidden="true" />
                  {t('plugins.configDialog.importJsonFile')}
                </Button>
                <Button
                  variant="ghost"
                  size="compact"
                  disabled={disabled || textDraft === null}
                  aria-label={t('plugins.configDialog.discardDraft', { name })}
                  onClick={discardTextDraft}
                >
                  {t('plugins.configDialog.discardDraftLabel')}
                </Button>
                <input
                  ref={fileInputRef}
                  type="file"
                  className="plugin-config-file-input"
                  accept=".json,application/json"
                  disabled={disabled}
                  aria-label={t('plugins.configDialog.importJsonFile', { name })}
                  onChange={onFileSelected}
                />
              </div>
            </div>
          ) : (
            <TextInput
              id={fieldId}
              value={textValue}
              disabled={disabled}
              aria-label={name}
              onChange={(e) => applyText(e.target.value)}
            />
          )}

          {element.description ? (
            <span className="inline-hint">{element.description}</span>
          ) : null}
          {fieldError ? (
            <span className="inline-hint danger" role="alert">
              {fieldError}
            </span>
          ) : null}
          {fileError ? (
            <span className="inline-hint danger" role="alert">
              {fileError}
            </span>
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
          <div className="plugin-config-field-header">
            <FieldLabel>{name}</FieldLabel>
            {unsetOptionalButton}
          </div>
          <TextInput
            id={fieldId}
            value={valStr}
            disabled={disabled}
            aria-label={name}
            onChange={(e) => {
              editor.onNumberDraftChange(path, fieldKey, e.target.value, isInteger)
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
              {renderObjectProperties(element, path)}
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
          <div className="plugin-config-field-header">
            <FieldLabel>{name}</FieldLabel>
            {unsetOptionalButton}
          </div>
          <TextArea
            id={fieldId}
            value={rawSnippet}
            disabled={disabled}
            aria-label={name}
            rows={4}
            onChange={(e) => {
              editor.onRawSnippetChange(path, fieldKey, e.target.value, 'object')
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
      const objectItems = element.items.kind === 'object' ? element.items : null
      const arrayNode = node && node.type === 'array' ? node : undefined
      // 受控 object 条目按 schema 结构化展开；其余数组与非法存值一律保留显式 Raw JSON 编辑。
      const structured =
        objectItems !== null && !rawMode && (node === undefined || arrayNode !== undefined)

      // 本数组子树（含自身）存在未解决的草稿错误时不允许切换结构化/Raw：切换会卸载结构化编辑视图，
      // 自动丢弃会让用户的无效输入与错误提示一起消失，因此先要求修复或显式丢弃。
      const subtreeHasDraftErrors = Object.keys(errors).some((errorKey) =>
        isDraftKeyUnderPath(errorKey, path),
      )
      const toggleMode = () => {
        if (subtreeHasDraftErrors) {
          return
        }
        fileReadTokenRef.current += 1
        editor.onFieldDraftDiscard(fieldKey)
        setRawMode((prev) => !prev)
      }

      if (!structured) {
        const rawSnippet =
          drafts[fieldKey] !== undefined
            ? drafts[fieldKey]
            : node
              ? getNodeSlice(rawJson, node)
              : '[]'

        return (
          <div className="form-group" key={name}>
            <div className="plugin-config-field-header">
              <FieldLabel>{name}</FieldLabel>
              {objectItems ? (
                <Button
                  variant="ghost"
                  size="compact"
                  disabled={disabled || subtreeHasDraftErrors}
                  aria-describedby={
                    subtreeHasDraftErrors ? `${fieldId}-draft-blocked` : undefined
                  }
                  aria-label={t('plugins.configDialog.toggleArrayEditor', { name })}
                  onClick={toggleMode}
                >
                  {t('plugins.configDialog.arrayStructuredMode')}
                </Button>
              ) : null}
            </div>
            {subtreeHasDraftErrors ? (
              <span id={`${fieldId}-draft-blocked`} className="inline-hint danger">
                {t('plugins.configDialog.resolveArrayDraftErrors', { name })}
              </span>
            ) : null}
            <TextArea
              id={fieldId}
              value={rawSnippet}
              disabled={disabled}
              aria-label={name}
              rows={3}
              onChange={(e) => {
                editor.onRawSnippetChange(path, fieldKey, e.target.value, 'array')
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

      const items = arrayNode?.children ?? []
      // 删除会让其后条目移位，因此存在未解决的草稿错误时先要求用户明确处理，绝不静默丢弃输入。
      const removalBlocked = Object.keys(errors).length > 0

      return (
        <fieldset key={name} className="plugin-config-fieldset plugin-config-array-field">
          <legend>{name}</legend>
          {element.description ? (
            <p className="inline-hint" style={{ marginTop: 0 }}>
              {element.description}
            </p>
          ) : null}

          <div className="plugin-config-array-items">
            {items.map((_itemNode, index) => (
              <fieldset
                key={`${index}:${structureGeneration}`}
                className="plugin-config-array-item"
              >
                <legend>{`${name}[${index}]`}</legend>
                <div className="plugin-config-array-item-actions">
                  <Button
                    variant="ghost"
                    size="compact"
                    danger
                    disabled={disabled || removalBlocked}
                    aria-label={t('plugins.configDialog.arrayRemoveItem', { name, index })}
                    onClick={() => editor.onArrayItemRemove(path, index)}
                  >
                    <Trash2 size={14} aria-hidden="true" />
                    {t('plugins.configDialog.arrayRemoveItemLabel')}
                  </Button>
                </div>
                <div className="plugin-config-field-group">
                  {renderObjectProperties(objectItems, [...path, index])}
                </div>
              </fieldset>
            ))}
          </div>

          {removalBlocked ? (
            <span className="inline-hint">
              {t('plugins.configDialog.structureEditsBlocked', { name })}
            </span>
          ) : null}

          <div className="plugin-config-array-actions">
            <Button
              size="compact"
              disabled={disabled}
              aria-label={t('plugins.configDialog.arrayAddItem', { name })}
              onClick={() => editor.onArrayItemAppend(path, buildSchemaPlaceholder(objectItems))}
            >
              <Plus size={14} aria-hidden="true" />
              {t('plugins.configDialog.arrayAddItem', { name })}
            </Button>
            <Button
              variant="ghost"
              size="compact"
              disabled={disabled || subtreeHasDraftErrors}
              aria-describedby={
                subtreeHasDraftErrors ? `${fieldId}-draft-blocked` : undefined
              }
              aria-label={t('plugins.configDialog.toggleArrayEditor', { name })}
              onClick={toggleMode}
            >
              {t('plugins.configDialog.arrayRawMode')}
            </Button>
          </div>
          {subtreeHasDraftErrors ? (
            <span id={`${fieldId}-draft-blocked`} className="inline-hint danger">
              {t('plugins.configDialog.resolveArrayDraftErrors', { name })}
            </span>
          ) : null}
        </fieldset>
      )
    }
  }
}

/** 文件读取失败的可读文案：只说明规则与位置，不回显文件内容。 */
function fileErrorMessage(
  result: Extract<JsonFileReadResult, { ok: false }>,
  t: (key: string, values?: Record<string, string | number>) => string,
): string {
  switch (result.reason) {
    case 'too-large':
      return t('plugins.configDialog.importFileTooLarge', {
        max: MAX_PLUGIN_VALUES_UTF8_BYTES,
      })
    case 'not-utf8':
      return t('plugins.configDialog.importFileNotUtf8')
    case 'invalid-json':
      return t('plugins.configDialog.importFileInvalidJson', { error: result.detail ?? '' })
    case 'read-failed':
      return t('plugins.configDialog.importFileReadFailed')
  }
}
