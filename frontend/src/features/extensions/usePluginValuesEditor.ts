import { useMemo, useState } from 'react'
import type { JSONPath } from 'jsonc-parser'
import { useI18n } from '@/shared/i18n'
import { utf8ByteLength } from '@/shared/lib/value'
import {
  appendStrictJsonArrayItem,
  isValidJsonIntegerLiteral,
  isValidJsonNumberLiteral,
  MAX_PLUGIN_VALUES_UTF8_BYTES,
  parseStrictJsonContainer,
  parseStrictJsonObject,
  removeStrictJsonNode,
  updateStrictJsonField,
} from './plugin-config-json'
import { isDraftKeyUnderPath, withoutStaleDrafts } from './plugin-draft-keys'
import type { PluginSchemaEditor } from './SchemaElementRenderer'

/** 复制并去掉指定键；草稿与错误映射共用同一不可变更新方式。 */
function withoutKey<T>(record: Record<string, T>, key: string): Record<string, T> {
  const next = { ...record }
  delete next[key]
  return next
}

interface PluginValuesEditorOptions {
  initialValuesJson: string
}

/**
 * 插件 values 的编辑状态：原文、字段草稿与草稿错误、结构代数、在途文件读取。
 *
 * <p>valuesJson 始终是唯一事实源，草稿只服务于“用户正在输入但还不能落值”的中间态；
 * 非法草稿只报错，绝不用上一次的合法值顶替。数组增删会移动条目，因此删除后按代数重建
 * 被移动的条目子树，避免旧条目的局部编辑状态落到移位后的条目上。
 */
export function usePluginValuesEditor({ initialValuesJson }: PluginValuesEditorOptions) {
  const { t } = useI18n()
  // 编辑器按文本处理以保留数值；服务端校验后存储和返回补默认值、压缩空白的规范文本。
  const [valuesJson, setValuesJson] = useState(initialValuesJson)
  const [fieldDrafts, setFieldDrafts] = useState<Record<string, string>>({})
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({})
  // 结构增删代数：让数组条目子树重建，避免旧条目的局部编辑状态与在途文件读取落到移位后的条目上
  const [structureGeneration, setStructureGeneration] = useState(0)
  // 在途的本地文件读取数量：父组件据此阻止提交，晚到的读取结果因此不可能落在已提交的旧值之后。
  const [pendingFileReads, setPendingFileReads] = useState(0)
  const [saveError, setSaveError] = useState<string | null>(null)

  // 严格验证 valuesJson
  const parseResult = useMemo(() => parseStrictJsonObject(valuesJson), [valuesJson])
  const hasFieldErrors = Object.keys(fieldErrors).length > 0
  const firstFieldError: string | undefined = Object.values(fieldErrors)[0]

  // POST 前的 UTF-8 字节预算：判定对象就是即将提交的 valuesJson 原文（含嵌入字符串的转义开销）。
  const valuesByteLength = useMemo(() => utf8ByteLength(valuesJson), [valuesJson])
  const valuesTooLarge = valuesByteLength > MAX_PLUGIN_VALUES_UTF8_BYTES

  function handleFieldScalarChange(path: JSONPath, value: unknown) {
    const res = updateStrictJsonField({ rawJson: valuesJson, path, value })
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

  // 表单子树里的草稿错误（键即字段路径）都会拦住离开表单模式与提交，绝不替换成上次的合法值。
  const draftsBlockForm = Object.keys(fieldErrors).some((key) => isDraftKeyUnderPath(key, []))

  /** 直接编辑 JSON 原文：草稿只对表单有意义，因此原文一变就整体丢弃。 */
  function replaceValuesJson(text: string) {
    setValuesJson(text)
    setFieldDrafts({})
    setFieldErrors({})
    setSaveError(null)
  }

  return {
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
    readingFiles: pendingFileReads > 0,
    saveError,
    setSaveError,
  }
}
