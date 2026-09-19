import { ModalBackdrop, ModalHeader } from '@/shared/ui/console/AiConsoleModalLayout'
import { useI18n } from '@/shared/i18n'
import type { EnvironmentCardDTO } from '@/shared/api/contracts/ai-environment'
import { EnvironmentHostSection } from '@/features/ai/environment/EnvironmentHostSection'

export interface EnvironmentManagementModalProps {
  environment: EnvironmentCardDTO
  onClose: () => void
}

export function EnvironmentManagementModal({
  environment,
  onClose,
}: EnvironmentManagementModalProps) {
  const { t } = useI18n()

  return (
    <ModalBackdrop onClose={onClose}>
      <div
        className="modal-card env-management-modal-card"
        role="dialog"
        aria-modal="true"
        aria-label={`${t('ai.environment.managementTitle')} - ${environment.name}`}
        onMouseDown={(e) => e.stopPropagation()}
      >
        <ModalHeader
          title={`${t('ai.environment.managementTitle')} - ${environment.name}`}
          onClose={onClose}
        />

        <div className="modal-body env-mgmt-body">
          <EnvironmentHostSection environment={environment} />
        </div>
      </div>
    </ModalBackdrop>
  )
}
