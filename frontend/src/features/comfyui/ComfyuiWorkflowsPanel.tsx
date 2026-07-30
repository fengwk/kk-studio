import { CreateCard } from '@/features/ai/shared/AiConsoleCommonCards'
import { ComfyuiWorkflowCard } from '@/features/comfyui/ComfyuiWorkflowCard'
import type { ComfyuiWorkflowApiDTO } from '@/shared/api/contracts'

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
  return (
    <div className="cards-grid">
      <CreateCard title="新建 ComfyUI Workflow" subtitle="配置 API-format workflow、输入绑定与结果选择器" onClick={onCreate} />
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
