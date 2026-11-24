import { X } from 'lucide-react'
import { useI18n } from '@/shared/i18n'
import type { RefObject } from 'react'

interface TagInputFieldProps {
  containerClassName: string
  committed: (number | string)[]
  inputValue: string
  isInvalid: boolean
  disabled: boolean
  inputId: string
  inputRef: RefObject<HTMLInputElement | null>
  placeholder?: string
  ariaLabel?: string
  localError: string | null
  onContainerClick: () => void
  onInputChange: (nextText: string) => void
  onKeyDown: (event: React.KeyboardEvent<HTMLInputElement>) => void
  onBlur: () => void
  onPaste: (event: React.ClipboardEvent<HTMLInputElement>) => void
  onRemove: (index: number) => void
}

/**
 * TagInput 的呈现：已提交标签、移除按钮、单行输入与本地校验提示。
 *
 * <p>点击容器空白处把焦点交给输入框；禁用时不渲染移除按钮。
 */
export function TagInputField({
  containerClassName,
  committed,
  inputValue,
  isInvalid,
  disabled,
  inputId,
  inputRef,
  placeholder,
  ariaLabel,
  localError,
  onContainerClick,
  onInputChange,
  onKeyDown,
  onBlur,
  onPaste,
  onRemove,
}: TagInputFieldProps) {
  const { t } = useI18n()
  return (
    <div className="ui-tag-input-container">
      <div
        className={containerClassName}
        data-ui-focus="within"
        data-invalid={isInvalid || undefined}
        onClick={onContainerClick}
      >
        {committed.map((item, index) => (
          <span key={`${item}-${index}`} className="ui-tag-item">
            <span>{String(item)}</span>
            {!disabled && (
              <button
                type="button"
                className="ui-tag-remove"
                aria-label={t('controls.tagInput.remove', { item: String(item) })}
                onClick={(event) => {
                  event.stopPropagation()
                  onRemove(index)
                }}
              >
                <X size={12} aria-hidden="true" />
              </button>
            )}
          </span>
        ))}

        <input
          ref={inputRef}
          id={inputId}
          type="text"
          className="ui-tag-input-field"
          data-ui-focus="delegate"
          value={inputValue}
          onChange={(event) => onInputChange(event.target.value)}
          onKeyDown={onKeyDown}
          onBlur={onBlur}
          onPaste={onPaste}
          placeholder={committed.length === 0 ? placeholder : undefined}
          disabled={disabled}
          aria-label={ariaLabel}
          aria-invalid={isInvalid}
        />
      </div>

      {localError ? (
        <span className="field-error" role="alert" aria-live="polite">
          {localError}
        </span>
      ) : null}
    </div>
  )
}
