import { useId, type ComponentProps, type ReactNode } from 'react'
import { FieldLabel } from '@/shared/ui/controls/FieldLabel'
import { FieldError } from '@/shared/ui/controls/FieldError'
import { TextInput } from '@/shared/ui/controls/TextInput'
import './AccountField.css'

export function AccountField({ label, error, children }: {
  label: string
  error?: string
  children: (id: string, errorId?: string) => ReactNode
}) {
  const id = useId()
  const errorId = error ? `${id}-error` : undefined
  return <div className="account-field">
    <FieldLabel htmlFor={id}>{label}</FieldLabel>
    {children(id, errorId)}
    {error && <FieldError id={errorId} role="alert">{error}</FieldError>}
  </div>
}

export function AccountInput({ label, error, ...props }: ComponentProps<typeof TextInput> & { label: string; error?: string }) {
  return <AccountField label={label} error={error}>
    {(id, errorId) => <TextInput {...props} id={id} invalid={!!error} aria-describedby={errorId} />}
  </AccountField>
}
