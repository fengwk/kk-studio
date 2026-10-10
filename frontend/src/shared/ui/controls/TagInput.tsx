import {
  useState,
  useRef,
  useId,
  useCallback,
  useEffect,
  type KeyboardEvent,
  type ClipboardEvent,
} from 'react'
import { X } from 'lucide-react'
import { useI18n } from '@/shared/i18n'
import {
  parseIntegerListTokens,
  splitTagInputDraft,
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

  const { committed, pending } = splitTagInputDraft(value)
  const inputValue = pending ?? ''

  const [localError, setLocalError] = useState<string | null>(null)

  // External reset / cancel: clear error if pending text is empty or value changed externally
  const prevValueRef = useRef(value)
  useEffect(() => {
    if (prevValueRef.current !== value) {
      prevValueRef.current = value
      if (!pending) {
        setLocalError(null)
      }
    }
  }, [value, pending])

  const formatError = useCallback(
    (err: IntegerListValidationError): string => {
      switch (err.code) {
        case 'emptyToken':
          return t('controls.tagInput.emptyToken')
        case 'notInteger':
          return t('controls.tagInput.invalidToken', { token: String(err.token ?? '') })
        case 'outOfRange':
          return t('controls.tagInput.outOfRange', {
            token: String(err.token ?? ''),
            min: err.min != null ? err.min : '',
            max: err.max != null ? err.max : '',
          })
        case 'duplicate':
          return t('controls.tagInput.duplicate', { token: String(err.token ?? '') })
      }
    },
    [t],
  )

  const commitTokens = useCallback(
    (raw: string) => {
      const trimmed = raw.trim()
      if (trimmed === '') {
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
        // Errors retain exactly pending raw text unchanged
        onChange([...committed, raw])
      } else {
        setLocalError(null)
        onChange([...committed, ...valid])
      }
    },
    [committed, formatError, max, min, onChange, pending],
  )

  const handleInputChange = (nextText: string) => {
    if (localError) setLocalError(null)
    if (nextText === '') {
      onChange(committed)
    } else {
      onChange([...committed, nextText])
    }
  }

  const handleKeyDown = (event: KeyboardEvent<HTMLInputElement>) => {
    if (disabled) return

    // IME composition guard: do not commit tokens during composition
    if (event.nativeEvent.isComposing || event.key === 'Process') {
      return
    }

    if (event.key === 'Enter' || event.key === ',') {
      event.preventDefault()
      commitTokens(inputValue)
    } else if (event.key === 'Backspace' && inputValue === '' && committed.length > 0) {
      event.preventDefault()
      const nextCommitted = committed.slice(0, -1)
      setLocalError(null)
      onChange(nextCommitted)
    }
  }

  const handleBlur = () => {
    if (inputValue.trim() !== '') {
      commitTokens(inputValue)
    }
  }

  const handlePaste = (event: ClipboardEvent<HTMLInputElement>) => {
    if (disabled) return
    const pasted = event.clipboardData.getData('text')
    if (!pasted) return

    // If paste contains separators, process immediately as a batch (all-or-nothing)
    if (pasted.includes(',') || /\s/.test(pasted.trim())) {
      event.preventDefault()
      const input = inputRef.current
      const start = input?.selectionStart ?? inputValue.length
      const end = input?.selectionEnd ?? inputValue.length
      const combined = inputValue.slice(0, start) + pasted + inputValue.slice(end)

      const tokens = parseIntegerListTokens(combined)
      const { valid, error } = validateIntegerListTokens(tokens, committed, { min, max })

      if (error) {
        setLocalError(formatError(error))
        // All-or-nothing: retain full text in pending
        onChange([...committed, combined])
      } else {
        setLocalError(null)
        onChange([...committed, ...valid])
      }
    }
  }

  const removeTag = (index: number) => {
    if (disabled) return
    const nextCommitted = committed.filter((_, i) => i !== index)
    setLocalError(null)
    onChange(pending !== null ? [...nextCommitted, pending] : nextCommitted)
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
        {committed.map((item, index) => (
          <span key={`${item}-${index}`} className="ui-tag-item">
            <span>{String(item)}</span>
            {!disabled && (
              <button
                type="button"
                className="ui-tag-remove"
                aria-label={t('controls.tagInput.remove', { item: String(item) })}
                onClick={(e) => {
                  e.stopPropagation()
                  removeTag(index)
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
          value={inputValue}
          onChange={(e) => handleInputChange(e.target.value)}
          onKeyDown={handleKeyDown}
          onBlur={handleBlur}
          onPaste={handlePaste}
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
