import {
  useState,
  useRef,
  useId,
  useCallback,
  useMemo,
  type KeyboardEvent,
  type ClipboardEvent,
} from 'react'
import { useI18n } from '@/shared/i18n'
import {
  parseIntegerListTokens,
  validateIntegerListTokens,
  IntegerListValidationError,
} from '@/shared/lib/integer-list'
import './controls.css'

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
  const { t } = useI18n()

  const [inputValue, setInputValue] = useState('')
  const [localError, setLocalError] = useState<string | null>(null)

  const items = useMemo(() => (Array.isArray(value) ? value : []), [value])

  const formatError = useCallback(
    (err: IntegerListValidationError): string => {
      switch (err.code) {
        case 'emptyToken':
          return t('settings.error.integerListEmptyToken')
        case 'notInteger':
          return t('settings.error.integerListInvalidToken', { token: String(err.token ?? '') })
        case 'outOfRange':
          return t('settings.error.integerListOutOfRange', {
            token: String(err.token ?? ''),
            min: err.min != null ? err.min : '',
            max: err.max != null ? err.max : '',
          })
        case 'duplicate':
          return t('settings.error.integerListDuplicate', { token: String(err.token ?? '') })
      }
    },
    [t],
  )

  const commitInput = useCallback(
    (raw: string) => {
      const trimmed = raw.trim()
      if (trimmed === '') {
        setInputValue('')
        setLocalError(null)
        // Clean any pending invalid string from items if it was added
        const cleaned = items.filter((item) => typeof item === 'number' || /^\d+$/u.test(String(item).trim()))
        if (cleaned.length !== items.length) {
          onChange(cleaned)
        }
        return
      }

      const tokens = parseIntegerListTokens(raw)
      const { valid, error } = validateIntegerListTokens(tokens, items, { min, max })

      if (error) {
        setLocalError(formatError(error))
        // Propagate raw invalid token so draft validation catches it and blocks save
        const filtered = items.filter((item) => typeof item === 'number')
        onChange([...filtered, trimmed])
      } else {
        setLocalError(null)
        setInputValue('')
        const filtered = items.filter((item) => typeof item === 'number')
        onChange([...filtered, ...valid])
      }
    },
    [formatError, items, max, min, onChange],
  )

  const handleKeyDown = (event: KeyboardEvent<HTMLInputElement>) => {
    if (disabled) return

    if (event.key === 'Enter' || event.key === ',') {
      event.preventDefault()
      commitInput(inputValue)
    } else if (event.key === 'Backspace' && inputValue === '' && items.length > 0) {
      event.preventDefault()
      const next = items.slice(0, -1)
      setLocalError(null)
      onChange(next)
    }
  }

  const handleBlur = () => {
    if (inputValue.trim() !== '') {
      commitInput(inputValue)
    }
  }

  const handlePaste = (event: ClipboardEvent<HTMLInputElement>) => {
    if (disabled) return
    const text = event.clipboardData.getData('text')
    if (!text) return

    // If paste contains separators, process immediately
    if (text.includes(',') || /\s/.test(text.trim())) {
      event.preventDefault()
      commitInput(text)
    }
  }

  const removeTag = (index: number) => {
    if (disabled) return
    const next = items.filter((_, i) => i !== index)
    setLocalError(null)
    onChange(next)
  }

  const isInvalid = Boolean(invalid || localError)

  return (
    <div className="ui-tag-input-container">
      <div
        className={['ui-tag-input', disabled && 'is-disabled', className]
          .filter(Boolean)
          .join(' ')}
        data-invalid={isInvalid || undefined}
        onClick={() => inputRef.current?.focus()}
      >
        {items.map((item, index) => {
          const isItemInvalid =
            typeof item === 'string' &&
            (!/^\d+$/u.test(item) ||
              (min != null && Number(item) < min) ||
              (max != null && Number(item) > max))
          return (
            <span
              key={`${item}-${index}`}
              className={['ui-tag-item', isItemInvalid && 'is-invalid'].filter(Boolean).join(' ')}
            >
              <span>{String(item)}</span>
              {!disabled && (
                <button
                  type="button"
                  className="ui-tag-remove"
                  aria-label={`Remove ${item}`}
                  onClick={(e) => {
                    e.stopPropagation()
                    removeTag(index)
                  }}
                >
                  ×
                </button>
              )}
            </span>
          )
        })}

        <input
          ref={inputRef}
          id={inputId}
          type="text"
          className="ui-tag-inline-input"
          value={inputValue}
          onChange={(e) => {
            setInputValue(e.target.value)
            if (localError) setLocalError(null)
          }}
          onKeyDown={handleKeyDown}
          onBlur={handleBlur}
          onPaste={handlePaste}
          placeholder={items.length === 0 ? placeholder : undefined}
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
