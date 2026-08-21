import { describe, expect, it } from 'vitest'
import { emptyAgentDraft } from '@/features/ai/catalog/ai-agent-draft-codec'
import { emptyModelDraft } from '@/features/ai/catalog/ai-model-draft-codec'
import { buildResourceSubmitPlan } from '@/features/ai/catalog/ai-resource-editor-submit-plans'
import { emptyProviderDraft } from '@/features/ai/catalog/ai-provider-draft-codec'

function drafts() {
  return {
    providerDraft: emptyProviderDraft(),
    modelDraft: emptyModelDraft(),
    agentDraft: emptyAgentDraft(),
  }
}

describe('ai-resource-editor-submit-plans', () => {
  it('keeps the edit Model PUT target at the original provider and model identity', () => {
    const plan = buildResourceSubmitPlan(
      {
        kind: 'model',
        mode: 'edit',
        providerName: 'deleted-provider',
        name: 'original-model',
        expectedVersion: '7',
      },
      {
        ...drafts(),
        modelDraft: {
          ...emptyModelDraft({ name: 'unrelated-provider' }),
          providerName: 'unrelated-provider',
          name: 'unrelated-model',
        },
      },
    )

    expect(plan).toMatchObject({
      kind: 'model',
      mode: 'edit',
      providerName: 'deleted-provider',
      name: 'original-model',
      data: { expectedVersion: '7' },
    })
    if (plan.kind !== 'model' || plan.mode !== 'edit') return
    expect(plan.data).not.toHaveProperty('providerName')
    expect(plan.data).not.toHaveProperty('name')
  })

  it('puts the edited Agent model in the update body', () => {
    const plan = buildResourceSubmitPlan(
      {
        kind: 'agent',
        mode: 'edit',
        name: 'assistant',
        model: 'deleted/original-model',
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
  })
})
