import { useState, type FormEvent } from 'react'
import { Dialog } from '@/shared/ui/overlays/Dialog'
import { TextInput } from '@/shared/ui/controls/TextInput'
import { FieldLabel } from '@/shared/ui/controls/FieldLabel'
import { Button } from '@/shared/ui/controls/Button'
import { useI18n } from '@/shared/i18n'

export interface OpenDirectoryModalProps {
  currentDirectory: string
  pending?: boolean
  error?: string | null
  onConfirm: (directory: string) => void
  onClose: () => void
}

/**
 * 宿主目录打开弹窗。
 * 输入宿主绝对路径并触发 IDE 目录切换；不使用浏览器本地 input webkitdirectory。
 */
export function OpenDirectoryModal({
  currentDirectory,
  pending = false,
  error = null,
  onConfirm,
  onClose,
}: OpenDirectoryModalProps) {
  const { t } = useI18n()
  const [directory, setDirectory] = useState(currentDirectory)

  const handleSubmit = (event: FormEvent) => {
    event.preventDefault()
    const trimmed = directory.trim()
    if (trimmed) {
      onConfirm(trimmed)
    }
  }

  return (
    <Dialog
      title={t('ai.ide.openDirectoryTitle')}
      pending={pending}
      onClose={onClose}
      initialFocusSelector="input[data-testid='ide-directory-input']"
    >
      <form onSubmit={handleSubmit} className="ide-directory-form">
        <div className="ide-directory-form-field">
          <FieldLabel htmlFor="ide-directory-input">
            {t('ai.ide.directoryPathLabel')}
          </FieldLabel>
          <TextInput
            id="ide-directory-input"
            data-testid="ide-directory-input"
            value={directory}
            disabled={pending}
            placeholder={t('ai.ide.directoryPathPlaceholder')}
            onChange={(e) => setDirectory(e.target.value)}
            autoFocus
          />
        </div>
        {error ? (
          <div className="ide-directory-error" role="alert" data-testid="ide-directory-error">
            {error}
          </div>
        ) : null}
        <div className="ide-directory-actions">
          <Button type="button" variant="ghost" disabled={pending} onClick={onClose}>
            {t('ai.ide.cancel')}
          </Button>
          <Button type="submit" disabled={pending || !directory.trim()} data-testid="ide-directory-confirm">
            {t('ai.ide.confirm')}
          </Button>
        </div>
      </form>
    </Dialog>
  )
}
