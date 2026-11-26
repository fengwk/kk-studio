import { useCallback, useLayoutEffect, useState } from 'react'
import { restoreComposerDraft, storeComposerDraft } from '@/features/thread/composer/composer-draft'
import { hasMessageContent, type ComposerPart } from '@/features/thread/composer/composer-parts'

export function usePaneDraft(scope: string, onUnsentChange: (unsent: boolean) => void, pendingSend: boolean) {
  const [parts, setParts] = useState(() => restoreComposerDraft(scope, []))
  const unsent = pendingSend || hasMessageContent(parts)
  useLayoutEffect(() => { onUnsentChange(unsent) }, [onUnsentChange, unsent])

  const changeParts = useCallback((next: ComposerPart[]) => {
    storeComposerDraft(scope, next)
    setParts(next)
  }, [scope])

  return [parts, changeParts] as const
}
