import { ArrowDown, ArrowUp, Trash2 } from 'lucide-react'
import { useI18n } from '@/shared/i18n'
import { Button } from '@/shared/ui/controls/Button'
import { ModelSelectionPicker } from '@/shared/ui/controls/ModelSelectionPicker'
import { newGroupMemberDraft } from '@/features/catalog/ai-model-group-draft-codec'
import { variantOptionsFromModel } from '@/shared/lib/model-variant-options'
import type { ModelGroupDraft } from '@/features/catalog/ai-console-types'
import type { SingleModelDTO } from '@/shared/api/generated/contracts'

type GroupMember = ModelGroupDraft['members'][number]

/**
 * 模型组的成员列表：逐行选择模型/变体，上移下移与删除。
 *
 * 换模型时把变体重置到该模型的默认变体（缺省取第一个），避免上一模型的变体串到新模型上。
 */
export function ModelGroupMembersSection({
  draft,
  candidates,
  onChange,
}: {
  draft: ModelGroupDraft
  candidates: SingleModelDTO[]
  onChange: (draft: ModelGroupDraft) => void
}) {
  const { t } = useI18n()

  function updateMember(index: number, patch: Partial<GroupMember>) {
    onChange({
      ...draft,
      members: draft.members.map((member, memberIndex) =>
        memberIndex === index ? { ...member, ...patch } : member),
    })
  }

  function move(index: number, delta: -1 | 1) {
    const target = index + delta
    if (target < 0 || target >= draft.members.length) {
      return
    }
    const next = [...draft.members]
    const [member] = next.splice(index, 1)
    next.splice(target, 0, member!)
    onChange({ ...draft, members: next })
  }

  return (
    <>
      <div className="structured-section-head">
        <h3 id="model-group-members-heading">{t('ai.catalog.form.groupMembers')}</h3>
        <Button
          variant="secondary"
          onClick={() => onChange({ ...draft, members: [...draft.members, newGroupMemberDraft()] })}
        >
          {t('ai.catalog.form.addGroupMember')}
        </Button>
      </div>
      {candidates.length === 0 ? (
        <span className="inline-hint" role="status">{t('ai.catalog.form.noGroupModels')}</span>
      ) : null}
      {draft.members.length === 0 ? (
        <span className="field-error">{t('ai.catalog.form.groupMembersRequired')}</span>
      ) : null}
      <div className="group-member-list">
        {draft.members.map((member, index) => {
          const selected = candidates.find((model) => model.name === member.modelName)
          const variants = variantOptionsFromModel(selected)
          return (
            <div className="group-member-row" key={member.draftId}>
              <span className="group-member-index">{index + 1}</span>
              <ModelSelectionPicker
                models={candidates}
                modelName={member.modelName}
                variant={member.variant}
                modelLabel={t('ai.catalog.form.groupMemberModel', { index: index + 1 })}
                variantLabel={t('ai.catalog.form.groupMemberVariant', { index: index + 1 })}
                modelPlaceholder={t('ai.catalog.form.groupMemberModelPlaceholder')}
                variantPlaceholder={t('ai.catalog.form.groupMemberVariantPlaceholder')}
                allowModelGroup={false}
                showVariant
                variantDisabled={!selected}
                variantUnavailable={Boolean(member.variant) && !variants.includes(member.variant)}
                onModelChange={(modelName) => {
                  const nextModel = candidates.find((model) => model.name === modelName)
                  const defaultVariant = nextModel?.config?.defaultVariant
                    ?? variantOptionsFromModel(nextModel)[0]
                    ?? ''
                  updateMember(index, { modelName, variant: defaultVariant })
                }}
                onVariantChange={(variant) => updateMember(index, { variant })}
              />
              <div className="group-member-actions">
                <Button
                  variant="secondary"
                  size="compact"
                  disabled={index === 0}
                  aria-label={t('ai.catalog.form.moveGroupMemberUp')}
                  onClick={() => move(index, -1)}
                >
                  <ArrowUp aria-hidden="true" />
                </Button>
                <Button
                  variant="secondary"
                  size="compact"
                  disabled={index === draft.members.length - 1}
                  aria-label={t('ai.catalog.form.moveGroupMemberDown')}
                  onClick={() => move(index, 1)}
                >
                  <ArrowDown aria-hidden="true" />
                </Button>
                <Button
                  variant="secondary"
                  size="compact"
                  danger
                  aria-label={t('ai.catalog.form.removeGroupMember', { index: index + 1 })}
                  onClick={() => onChange({
                    ...draft,
                    members: draft.members.filter((_, memberIndex) => memberIndex !== index),
                  })}
                >
                  <Trash2 aria-hidden="true" />
                </Button>
              </div>
            </div>
          )
        })}
      </div>
    </>
  )
}
