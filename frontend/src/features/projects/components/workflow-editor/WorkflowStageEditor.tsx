import { useI18n } from '@/shared/i18n'
import { StateBlock } from '@/shared/ui/feedback/StateBlock'
import type { WorkflowDraftState } from '../../workflow-draft'
import { WorkflowStageForm } from './WorkflowStageForm'
import { WorkflowStageList } from './WorkflowStageList'

interface WorkflowStageEditorProps {
  draftStates: WorkflowDraftState[]
  selectedKey: string | null
  /** 被 Issue 或阻塞恢复点引用的阶段编码：不可删除、不可改码。 */
  referencedStates: ReadonlySet<string>
  constraintsReady: boolean
  isPending: boolean
  agentOptions: string[]
  agentsLoading: boolean
  environments: ReadonlyArray<{ name: string }>
  onSelect: (key: string) => void
  onRename: (key: string, rawCode: string) => void
  onUpdate: (key: string, patch: Partial<WorkflowDraftState>) => void
  onToggleNext: (key: string, target: string, checked: boolean) => void
  onMove: (index: number, delta: number) => void
  onAdd: () => void
  onRemove: (key: string) => void
}

/**
 * 工作流阶段编辑器：阶段顺序列表与选中阶段的属性表单。
 * 增删改排与图自洽规则由父级草稿状态维护，这里只呈现与回调。
 */
export function WorkflowStageEditor({
  draftStates,
  selectedKey,
  referencedStates,
  constraintsReady,
  isPending,
  agentOptions,
  agentsLoading,
  environments,
  onSelect,
  onRename,
  onUpdate,
  onToggleNext,
  onMove,
  onAdd,
  onRemove,
}: WorkflowStageEditorProps) {
  const { t } = useI18n()
  // 未显式选中时默认展示首个阶段，避免右侧空白。
  const selectedState = draftStates.find((state) => state.key === selectedKey) ?? draftStates[0] ?? null

  return (
    <div className="workflow-editor">
      <WorkflowStageList
        draftStates={draftStates}
        selectedKey={selectedKey}
        referencedStates={referencedStates}
        constraintsReady={constraintsReady}
        isPending={isPending}
        onSelect={onSelect}
        onMove={onMove}
        onAdd={onAdd}
        onRemove={onRemove}
      />

      {selectedState ? (
        <WorkflowStageForm
          state={selectedState}
          draftStates={draftStates}
          referencedStates={referencedStates}
          constraintsReady={constraintsReady}
          isPending={isPending}
          agentOptions={agentOptions}
          agentsLoading={agentsLoading}
          environments={environments}
          onRename={onRename}
          onUpdate={onUpdate}
          onToggleNext={onToggleNext}
        />
      ) : (
        <StateBlock title={t('projects.edit.selectStage')} />
      )}
    </div>
  )
}
