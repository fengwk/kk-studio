import { CreateCard } from '@/shared/ui/console/AiConsoleCommonCards'
import { ComfyuiWorkflowCard } from '@/features/comfyui/ComfyuiWorkflowCard'
import type { ComfyuiWorkflowApiDTO } from '@/shared/api/contracts/comfyui'
import { useI18n } from '@/shared/i18n'

export function ComfyuiWorkflowsPanel({
  workflows,
  deletePending,
  onCreate,
  onRun,
  onEdit,
  onDelete,
}: {
  workflows: ComfyuiWorkflowApiDTO[]
  deletePending: boolean
  onCreate: () => void
  onRun: (workflow: ComfyuiWorkflowApiDTO) => void
  onEdit: (workflow: ComfyuiWorkflowApiDTO) => void
  onDelete: (workflow: ComfyuiWorkflowApiDTO) => void
}) {
  const { t } = useI18n()
  return (
    <div className="cards-grid">
      <CreateCard
        title={t('comfyui.workflows.createTitle')}
        subtitle={t('comfyui.workflows.createSubtitle')}
        onClick={onCreate}
      />
      {workflows.map((workflow) => (
        <ComfyuiWorkflowCard
          key={workflow.id}
          workflow={workflow}
          deletePending={deletePending}
          onRun={() => onRun(workflow)}
          onEdit={() => onEdit(workflow)}
          onDelete={() => onDelete(workflow)}
        />
      ))}
    </div>
  )
}
