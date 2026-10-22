import { describe, expect, it } from 'vitest'
import { emptyAgentDraft } from '@/features/catalog/ai-agent-draft-codec'
import { emptyModelGroupDraft } from '@/features/catalog/ai-model-group-draft-codec'
import { emptyModelDraft } from '@/features/catalog/ai-model-draft-codec'
import { buildResourceSubmitPlan } from '@/features/catalog/ai-resource-editor-submit-plans'
import { emptyProviderDraft } from '@/features/catalog/ai-provider-draft-codec'

function drafts() {
  return {
    providerDraft: emptyProviderDraft(),
    modelDraft: emptyModelDraft(),
    modelGroupDraft: emptyModelGroupDraft(),
    agentDraft: emptyAgentDraft(),
  }
}

describe('ai-resource-editor-submit-plans', () => {
  it('keeps the edit Model PUT target at the original logical name while placing renamed name in update data', () => {
    const plan = buildResourceSubmitPlan(
      {
        kind: 'model',
        mode: 'edit', resourceKind: 'MODEL', name: 'original-model',
        expectedVersion: '7',
      },
      {
        ...drafts(),
        modelDraft: {
          ...emptyModelDraft({ name: 'unrelated-provider' }),
          providerName: 'unrelated-provider',
          name: 'renamed-model',
          modelId: 'unrelated-wire-model',
        },
      },
    )

    expect(plan).toMatchObject({
      kind: 'model',
      mode: 'edit', name: 'original-model',
      data: { expectedVersion: '7', name: 'renamed-model' },
    })
    if (plan.kind !== 'model' || plan.mode !== 'edit') return
    expect(plan.data).toHaveProperty('kind', 'MODEL')
    // PUT 整体替换：Provider 是必填属性而不是路径身份。
    expect(plan.data).toHaveProperty('providerName', 'unrelated-provider')
    expect(plan.data.name).toBe('renamed-model')
  })

  it('puts the edited Agent model in the update body', () => {
    const plan = buildResourceSubmitPlan(
      {
        kind: 'agent',
        mode: 'edit',
        name: 'assistant',
        type: 'USER',
        model: 'original-model',
        expectedVersion: '8',
      },
      {
        ...drafts(),
        agentDraft: {
          ...emptyAgentDraft(),
          model: 'unrelated/model',
          description: 'updated description',
        },
      },
    )

    expect(plan).toMatchObject({
      kind: 'agent',
      mode: 'edit',
      name: 'assistant',
      data: {
        expectedVersion: '8',
        description: 'updated description',
        model: 'unrelated/model',
      },
    })
    if (plan.kind !== 'agent' || plan.mode !== 'edit') return
    expect(plan.data).not.toHaveProperty('type')
  })

  it('writes a null model for an unconfigured BUILTIN and never sends the system-owned type', () => {
    const plan = buildResourceSubmitPlan(
      {
        kind: 'agent',
        mode: 'edit',
        name: 'summarizer',
        type: 'BUILTIN',
        model: null,
        expectedVersion: '3',
      },
      {
        ...drafts(),
        agentDraft: { ...emptyAgentDraft(), description: 'edited prompt' },
      },
    )

    expect(plan).toMatchObject({
      kind: 'agent',
      mode: 'edit',
      name: 'summarizer',
      data: { expectedVersion: '3', model: null, description: 'edited prompt' },
    })
    if (plan.kind !== 'agent' || plan.mode !== 'edit') return
    expect(plan.data).not.toHaveProperty('type')
  })
})
