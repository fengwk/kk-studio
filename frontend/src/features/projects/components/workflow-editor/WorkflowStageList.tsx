import { ArrowDown, ArrowUp, Plus, Trash2 } from 'lucide-react'
import { useI18n } from '@/shared/i18n'
import { Badge } from '@/shared/ui/data/Badge'
import { Button } from '@/shared/ui/controls/Button'
import { IconButton } from '@/shared/ui/controls/IconButton'
import { isReservedStateCode, type WorkflowDraftState } from '../../workflow-draft'

interface WorkflowStageListProps {
  draftStates: WorkflowDraftState[]
  selectedKey: string | null
  referencedStates: ReadonlySet<string>
  constraintsReady: boolean
  isPending: boolean
  onSelect: (key: string) => void
  onMove: (index: number, delta: number) => void
  onAdd: () => void
  onRemove: (key: string) => void
}

/**
 * 阶段顺序列表：每行显示序号、名称、编码徽标与执行模式徽标，并给出上移/下移/删除。
 *
 * <p>保留阶段与仍被引用的阶段不可删除，禁用原因作为按钮的 title：保留优先于引用。
 */
export function WorkflowStageList({
  draftStates,
  selectedKey,
  referencedStates,
  constraintsReady,
  isPending,
  onSelect,
  onMove,
  onAdd,
  onRemove,
}: WorkflowStageListProps) {
  const { t } = useI18n()
  const selectedState = draftStates.find((state) => state.key === selectedKey) ?? draftStates[0] ?? null

  return (
    <div className="workflow-stage-list" role="list" aria-label={t('projects.edit.stageListLabel')}>
      {draftStates.map((state, index) => {
        const reserved = isReservedStateCode(state.state)
        const active = selectedState?.key === state.key
        const label = state.name.trim() || state.state || t('projects.edit.untitledStage')
        const referenced =
          referencedStates.has(state.state)
          || draftStates.some((other) => other.key !== state.key && other.next.includes(state.state))
        const deleteDisabled = isPending || !constraintsReady || reserved || referenced
        const deleteReason = reserved
          ? t('projects.edit.deleteReservedReason')
          : referenced
            ? t('projects.edit.deleteReferencedReason')
            : undefined
        return (
          <div key={state.key} className={`workflow-stage-row${active ? ' is-active' : ''}`} role="listitem">
            <button
              type="button"
              className="workflow-stage-select"
              aria-pressed={active}
              onClick={() => onSelect(state.key)}
            >
              <span className="workflow-stage-order">{index + 1}</span>
              <span className="workflow-stage-label">{label}</span>
              <Badge tone="info" mono>{state.state || '—'}</Badge>
              {reserved ? (
                <Badge tone="neutral">{t('projects.edit.badgeReserved')}</Badge>
              ) : state.agent !== null ? (
                <Badge tone="accent">{t('projects.edit.badgeAgent')}</Badge>
              ) : (
                <Badge tone="neutral">{t('projects.edit.badgeManual')}</Badge>
              )}
              {!state.enabled && (
                <Badge tone="warning">{t('projects.edit.badgeDisabled')}</Badge>
              )}
            </button>
            <IconButton
              label={t('projects.edit.moveUp', { label })}
              size="compact"
              disabled={isPending || index === 0}
              onClick={() => onMove(index, -1)}
            >
              <ArrowUp aria-hidden="true" />
            </IconButton>
            <IconButton
              label={t('projects.edit.moveDown', { label })}
              size="compact"
              disabled={isPending || index === draftStates.length - 1}
              onClick={() => onMove(index, 1)}
            >
              <ArrowDown aria-hidden="true" />
            </IconButton>
            <IconButton
              label={t('projects.edit.deleteStage', { label })}
              size="compact"
              danger
              disabled={deleteDisabled}
              title={deleteReason}
              onClick={() => onRemove(state.key)}
            >
              <Trash2 aria-hidden="true" />
            </IconButton>
          </div>
        )
      })}

      <Button variant="ghost" size="compact" disabled={isPending} onClick={onAdd}>
        <Plus size={14} aria-hidden="true" />
        <span>{t('projects.edit.addStage')}</span>
      </Button>
    </div>
  )
}
