import { useId, useState, type ReactNode } from 'react'
import { Plus, Trash2 } from 'lucide-react'
import { Button } from '@/shared/ui/controls/Button'
import { Checkbox } from '@/shared/ui/controls/Checkbox'
import { FieldLabel } from '@/shared/ui/controls/FieldLabel'
import { Select } from '@/shared/ui/controls/Select'
import { TextArea } from '@/shared/ui/controls/TextArea'
import { TextInput } from '@/shared/ui/controls/TextInput'
import { useI18n } from '@/shared/i18n'
import { findNodeAtLocation, type JSONPath, type Node } from 'jsonc-parser'
import { getNodeSafeValue, getNodeSlice } from './plugin-config-json'
import { isDraftKeyUnderPath } from './plugin-draft-keys'
import { buildSchemaPlaceholder, type PluginSchemaElement } from './plugin-schema'
import { SchemaStringField } from './SchemaStringField'
import { UnsetOptionalFieldButton } from './UnsetOptionalFieldButton'

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
  const [rawMode, setRawMode] = useState(false)

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
  const showUnset = !required && node !== undefined
  const unsetOptionalButton = showUnset ? (
    <UnsetOptionalFieldButton
      name={name}
      path={path}
      disabled={disabled}
      onRemove={editor.onFieldRemove}
    />
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
            <div className="plugin-config-field-header">
              <FieldLabel>{name}</FieldLabel>
              {unsetOptionalButton}
            </div>
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

      return (
        <SchemaStringField
          key={name}
          name={name}
          path={path}
          fieldKey={fieldKey}
          fieldError={fieldError}
          currentSafeVal={currentSafeVal}
          description={element.description}
          disabled={disabled}
          unset={{ visible: showUnset }}
          editor={editor}
        />
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
