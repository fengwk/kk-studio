import { useMutation, useQueryClient } from '@tanstack/react-query'
import { useEffect, useId, useRef, useState } from 'react'
import { normalizeThreadName } from '@/features/thread/thread-name'
import { workspaceKeys } from '@/features/workspace/queries'
import { ApiError, apiErrorDetail, request } from '@/shared/api/client'
import { useI18n } from '@/shared/i18n'
import { Button } from '@/shared/ui/controls/Button'
import { FieldError } from '@/shared/ui/controls/FieldError'
import { FieldLabel } from '@/shared/ui/controls/FieldLabel'
import { TextInput } from '@/shared/ui/controls/TextInput'
import { Dialog } from '@/shared/ui/overlays/Dialog'

export function TurnCutDialog({ kind, initialName, spaceId, sessionId, sourceThreadId, turnEndEntryId, onCreated, onClose }: {
  kind: 'branch' | 'fork'
  initialName: string
  spaceId: string
  sessionId: string
  sourceThreadId: string
  turnEndEntryId: string
  onCreated: (threadId: string) => void
  onClose: () => void
}) {
  const { t } = useI18n()
  const inputId = useId()
  const inputRef = useRef<HTMLInputElement>(null)
  const cache = useQueryClient()
  const [name, setName] = useState(initialName)
  const title = normalizeThreadName(name)
  const [key, setKey] = useState(() => crypto.randomUUID())
  useEffect(() => {
    inputRef.current?.select()
  }, [])
  const create = useMutation({
    mutationFn: (title: string) => request(kind === 'branch'
      ? 'POST /api/chat-spaces/{spaceId}/sessions/{id}/branches'
      : 'POST /api/chat-spaces/{spaceId}/sessions/{id}/forks', {
      path: { spaceId, id: sessionId },
      body: { title, sourceThreadId, turnEndEntryId },
    }, { idempotencyKey: key }),
    onSuccess: () => { void cache.invalidateQueries({ queryKey: workspaceKeys.tree(spaceId) }) },
  })
  const nameConflict = create.error instanceof ApiError && [create.error.reason, create.error.code].includes('THREAD_NAME_EXISTS')
  const error = nameConflict ? t('ai.chat.branch.nameConflict')
    : create.error ? apiErrorDetail(create.error) ?? create.error.message : null
  return <Dialog className="turn-cut-dialog" ariaLabelledBy={`${inputId}-title`}
    onClose={onClose} pending={create.isPending} initialFocusSelector="input">
    <form className="turn-cut-form" onSubmit={(event) => {
      event.preventDefault()
      if (title != null && !create.isPending) create.mutate(title, { onSuccess: ({ threadId }) => onCreated(threadId) })
    }}>
      <h2 id={`${inputId}-title`}>{t(kind === 'branch' ? 'ai.chat.branch.fromHere' : 'ai.chat.branch.forkSession')}</h2>
      <div className="turn-cut-field">
        <FieldLabel htmlFor={inputId}>{t(kind === 'branch' ? 'ai.runtime.branch.branchName' : 'ai.runtime.branch.sessionName')}</FieldLabel>
        <TextInput ref={inputRef} id={inputId} value={name} required disabled={create.isPending}
          aria-invalid={error != null} aria-describedby={error ? `${inputId}-error` : undefined}
          onChange={(event) => {
            setName(event.target.value)
            if (create.isError) {
              create.reset()
              setKey(crypto.randomUUID())
            }
          }} />
        {error && <FieldError id={`${inputId}-error`} role="alert">{error}</FieldError>}
      </div>
      <div className="turn-cut-actions">
        <Button type="button" variant="secondary" onClick={onClose} disabled={create.isPending}>{t('shared.cancel')}</Button>
        <Button type="submit" disabled={title == null} loading={create.isPending}>{t('ai.runtime.branch.create')}</Button>
      </div>
    </form>
  </Dialog>
}
