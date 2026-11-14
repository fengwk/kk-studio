import { useCallback, useEffect, useRef, useState, type ClipboardEvent, type KeyboardEvent } from 'react'
import { parseIntegerListTokens, splitTagInputDraft, validateIntegerListTokens } from '@/shared/lib/integer-list'
import { useIntegerListErrorMessage } from './useIntegerListErrorMessage'

interface UseTagInputInput {
  value: (number | string)[]
  onChange: (items: (number | string)[]) => void
  min?: number
  max?: number
  disabled: boolean
  inputRef: React.RefObject<HTMLInputElement | null>
}

/**
 * TagInput 的编辑模型：已提交的整数项 + 至多一段待提交原文。
 *
 * <p>`value` 用「数字 = 已提交、字符串 = 待提交草稿」表达，因此每个按键都整体回写数组；
 * 校验失败时保留用户原文而不是丢弃输入，只有明确成功才把 token 转成数字。
 */
export function useTagInput({ value, onChange, min, max, disabled, inputRef }: UseTagInputInput) {
  const { committed, pending } = splitTagInputDraft(value)
  const inputValue = pending ?? ''
  const [localError, setLocalError] = useState<string | null>(null)
  const formatError = useIntegerListErrorMessage()

  // 外部重置或取消：值被外部改写且不再有待提交原文时，清掉上一次的校验错误。
  const prevValueRef = useRef(value)
  useEffect(() => {
    if (prevValueRef.current !== value) {
      prevValueRef.current = value
      if (!pending) {
        setLocalError(null)
      }
    }
  }, [value, pending])

  /** 提交一段原文：分离成 token 后逐项校验，失败则原文原样留在待提交位。 */
  const commitTokens = useCallback(
    (raw: string) => {
      if (raw.trim() === '') {
        setLocalError(null)
        if (pending !== null) {
          onChange(committed)
        }
        return
      }

      const tokens = parseIntegerListTokens(raw)
      const { valid, error } = validateIntegerListTokens(tokens, committed, { min, max })
      if (error) {
        setLocalError(formatError(error))
        onChange([...committed, raw])
        return
      }
      setLocalError(null)
      onChange([...committed, ...valid])
    },
    [committed, formatError, max, min, onChange, pending],
  )

  const handleInputChange = (nextText: string) => {
    if (localError) {
      setLocalError(null)
    }
    onChange(nextText === '' ? committed : [...committed, nextText])
  }

  const handleKeyDown = (event: KeyboardEvent<HTMLInputElement>) => {
    if (disabled) {
      return
    }
    // 输入法组合中的按键交给 IME，绝不在组合中途提交 token。
    if (event.nativeEvent.isComposing || event.key === 'Process') {
      return
    }
    if (event.key === 'Enter' || event.key === ',') {
      event.preventDefault()
      commitTokens(inputValue)
      return
    }
    if (event.key === 'Backspace' && inputValue === '' && committed.length > 0) {
      event.preventDefault()
      setLocalError(null)
      onChange(committed.slice(0, -1))
    }
  }

  const handleBlur = () => {
    if (inputValue.trim() !== '') {
      commitTokens(inputValue)
    }
  }

  /** 含分隔符的粘贴按整批处理（全有或全无）：拼到光标处后一次性校验。 */
  const handlePaste = (event: ClipboardEvent<HTMLInputElement>) => {
    if (disabled) {
      return
    }
    const pasted = event.clipboardData.getData('text')
    if (!pasted || !(pasted.includes(',') || /\s/.test(pasted.trim()))) {
      return
    }
    event.preventDefault()
    const input = inputRef.current
    const start = input?.selectionStart ?? inputValue.length
    const end = input?.selectionEnd ?? inputValue.length
    commitTokens(inputValue.slice(0, start) + pasted + inputValue.slice(end))
  }

  /** 移除一个已提交项；仍在编辑的原文保持不动。 */
  const removeAt = (index: number) => {
    if (disabled) {
      return
    }
    const nextCommitted = committed.filter((_, position) => position !== index)
    setLocalError(null)
    onChange(pending !== null ? [...nextCommitted, pending] : nextCommitted)
  }

  return {
    committed,
    inputValue,
    localError,
    handleInputChange,
    handleKeyDown,
    handleBlur,
    handlePaste,
    removeAt,
  }
}
