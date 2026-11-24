import { useId, useRef } from 'react'
import { TagInputField } from './TagInputField'
import { useTagInput } from './useTagInput'
import './TagInput.css'

export interface TagInputProps {
  id?: string
  value: (number | string)[]
  onChange: (items: (number | string)[]) => void
  min?: number
  max?: number
  placeholder?: string
  disabled?: boolean
  invalid?: boolean
  ariaLabel?: string
  className?: string
}

/**
 * 整数标签输入：回车或逗号提交，含分隔符的粘贴按整批校验。
 *
 * <p>编辑模型在 {@link useTagInput}，呈现交给 {@link TagInputField}；本组件只做连接。
 */
export function TagInput({
  id,
  value,
  onChange,
  min,
  max,
  placeholder,
  disabled = false,
  invalid = false,
  ariaLabel,
  className,
}: TagInputProps) {
  const generatedId = useId()
  const inputId = id ?? generatedId
  const inputRef = useRef<HTMLInputElement>(null)
  const tagInput = useTagInput({ value, onChange, min, max, disabled, inputRef })
  const isInvalid = Boolean(invalid || tagInput.localError)

  return (
    <TagInputField
      containerClassName={['ui-tag-input', disabled && 'is-disabled', className]
        .filter(Boolean)
        .join(' ')}
      committed={tagInput.committed}
      inputValue={tagInput.inputValue}
      isInvalid={isInvalid}
      disabled={disabled}
      inputId={inputId}
      inputRef={inputRef}
      placeholder={placeholder}
      ariaLabel={ariaLabel}
      localError={tagInput.localError}
      onContainerClick={() => inputRef.current?.focus()}
      onInputChange={tagInput.handleInputChange}
      onKeyDown={tagInput.handleKeyDown}
      onBlur={tagInput.handleBlur}
      onPaste={tagInput.handlePaste}
      onRemove={tagInput.removeAt}
    />
  )
}
