import { useState } from 'react'
import { useI18n } from '@/shared/i18n'
import { ConfirmActionModal } from '@/shared/ui/overlays/ConfirmActionModal'

/** 拦截用户关闭；保存等系统动作仍直接调用 onClose。 */
export function useUnsavedChangesConfirmation(
  dirty: boolean,
  pending: boolean,
  onClose: () => void,
) {
  const { t } = useI18n()
  const [confirming, setConfirming] = useState(false)

  function requestClose() {
    if (pending) return
    if (dirty) {
      setConfirming(true)
    } else {
      onClose()
    }
  }

  const confirmation = confirming ? (
    <ConfirmActionModal
      pending={pending}
      cancelLabel={t('shared.unsavedChanges.keepEditing')}
      onClose={() => setConfirming(false)}
      modal={{
        title: t('shared.unsavedChanges.title'),
        description: t('shared.unsavedChanges.description'),
        confirmLabel: t('shared.unsavedChanges.discard'),
        tone: 'danger',
        onConfirm: () => {
          setConfirming(false)
          onClose()
        },
      }}
    />
  ) : null

  return { requestClose, confirmation }
}
