import {
  useId,
  useLayoutEffect,
  useRef,
  useState,
  type ChangeEvent,
} from 'react'
import { FileUp } from 'lucide-react'
import { Button } from '@/shared/ui/controls/Button'
import { Checkbox } from '@/shared/ui/controls/Checkbox'
import { FieldLabel } from '@/shared/ui/controls/FieldLabel'
import { TextArea } from '@/shared/ui/controls/TextArea'
import { TextInput } from '@/shared/ui/controls/TextInput'
import { useI18n, type TranslationKey } from '@/shared/i18n'
import type { JSONPath } from 'jsonc-parser'
import { MAX_PLUGIN_VALUES_UTF8_BYTES } from './plugin-config-json'
import { readStrictJsonFile, type JsonFileReadResult } from './plugin-config-file'
import type { PluginSchemaEditor } from './SchemaElementRenderer'
import { UnsetOptionalFieldButton } from './UnsetOptionalFieldButton'

/** 文件读取失败的可读文案：只说明规则与位置，不回显文件内容。 */
function fileErrorMessage(
  result: Extract<JsonFileReadResult, { ok: false }>,
  t: (key: TranslationKey, values?: Record<string, string | number>) => string,
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

interface SchemaStringFieldProps {
  name: string
  path: JSONPath
  fieldKey: string
  fieldError: string | undefined
  currentSafeVal: unknown
  description: string | undefined
  disabled: boolean
  /** 可选字段：需要显示省略入口时传入（字段当前不存在则不显示）。 */
  unset: { visible: boolean } | null
  editor: PluginSchemaEditor
}

/**
 * 字符串字段：普通文本、多行/JSON 编辑器与本地文件导入。
 *
 * <p>局部状态（展开、JSON 校验、草稿、文件错误）只在单个字段实例内生效，绝不成为第二份配置。
 * 文件读取跨越 await，读取期间用户可能已经改过别的字段并让父组件写出新的 valuesJson，因此续跑
 * 只调用当前渲染的 editor，绝不使用发起读取那一刻捕获的旧回调；令牌让新编辑、模式切换或卸载
 * 之后的晚到结果失效。
 */
export function SchemaStringField({
  name,
  path,
  fieldKey,
  fieldError,
  currentSafeVal,
  description,
  disabled,
  unset,
  editor,
}: SchemaStringFieldProps) {
  const { t } = useI18n()
  const fieldId = useId()
  const [textEditorOpen, setTextEditorOpen] = useState(false)
  const [validateJsonText, setValidateJsonText] = useState(false)
  const [textDraft, setTextDraft] = useState<string | null>(null)
  const [fileError, setFileError] = useState<string | null>(null)
  const fileInputRef = useRef<HTMLInputElement | null>(null)
  // 在途文件读取令牌：本字段的新编辑、模式切换或卸载都会让晚到的读取结果失效。
  const fileReadTokenRef = useRef(0)
  const editorRef = useRef(editor)
  useLayoutEffect(() => {
    editorRef.current = editor
  })

  useLayoutEffect(() => {
    return () => {
      fileReadTokenRef.current += 1
    }
  }, [])

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
    editorRef.current.onFileReadStart()
    try {
      result = await readStrictJsonFile(file)
    } finally {
      editorRef.current.onFileReadEnd()
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
      editorRef.current.onStringEdit(path, fieldKey, result.text, false)
      return
    }
    setTextDraft(result.text)
    editorRef.current.onStringEdit(path, fieldKey, result.text, true)
  }

  return (
    <div className="form-group" key={name}>
      <div className="plugin-config-field-header">
        <FieldLabel>{name}</FieldLabel>
        <div className="plugin-config-field-actions">
          {unset?.visible ? (
            <UnsetOptionalFieldButton
              name={name}
              path={path}
              disabled={disabled}
              onBeforeRemove={() => {
                fileReadTokenRef.current += 1
                setTextDraft(null)
                setFileError(null)
              }}
              onRemove={editor.onFieldRemove}
            />
          ) : null}
          <Button
            variant="secondary"
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
              variant="secondary"
              size="compact"
              disabled={disabled}
              onClick={() => fileInputRef.current?.click()}
            >
              <FileUp size={14} aria-hidden="true" />
              {t('plugins.configDialog.importJsonFile')}
            </Button>
            <Button
              variant="secondary"
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

      {description ? (
        <span className="inline-hint">{description}</span>
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
