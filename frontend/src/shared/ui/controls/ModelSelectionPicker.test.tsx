import { fireEvent, render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { ModelSelectionPicker } from '@/shared/ui/controls/ModelSelectionPicker'
import type { ModelDTO } from '@/shared/api/generated/contracts'

const models: ModelDTO[] = [
  {
    type: 'MODEL',
    name: 'solo',
    providerName: 'provider',
    modelId: 'solo',
    description: null,
    config: {
      limit: { context: 1000, output: 100 },
      abilities: { tools: true, reasoning: false, midConversationSystemMessages: false, inputModalities: ['TEXT'] },
      pricing: { base: { input: '0', output: '0', cacheRead: '0', cacheWrite: '0', cacheWriteByTtl: {}, reasoning: null }, overrides: [], multipliers: [] },
      variants: [{ id: 'quick', promptCacheRetention: 'NONE' }],
      defaultVariant: 'quick',
    },
    version: '1',
    createdAt: 0,
    updatedAt: 0,
  },
  {
    type: 'GROUP',
    name: 'group',
    description: null,
    group: { members: [], fallbackStatusCodes: [], cooldownSeconds: 0 },
    config: {
      limit: { context: 1000, output: 100 },
      abilities: { tools: true, reasoning: false, midConversationSystemMessages: false, inputModalities: ['TEXT'] },
      pricing: { base: { input: '0', output: '0', cacheRead: '0', cacheWrite: '0', cacheWriteByTtl: {}, reasoning: null }, overrides: [], multipliers: [] },
      variants: [],
      defaultVariant: '',
    },
    version: '1',
    createdAt: 0,
    updatedAt: 0,
  },
]

describe('ModelSelectionPicker', () => {
  // 模型组应当可选但无 variant；换成具体模型时共享控件提供 variant。
  it('offers model groups when allowed and only renders variants for a concrete model', () => {
    const { rerender } = render(
      <ModelSelectionPicker
        models={models}
        modelName="solo"
        variant="quick"
        modelLabel="Model"
        variantLabel="Variant"
        allowModelGroup
        showVariant
        variantUnsetLabel="Use model default"
        onModelChange={() => {}}
        onVariantChange={() => {}}
      />,
    )
    fireEvent.click(screen.getByRole('button', { name: 'Model' }))
    expect(screen.getByRole('option', { name: 'group' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Variant' })).toBeInTheDocument()

    rerender(
      <ModelSelectionPicker
        models={models}
        modelName="group"
        variant=""
        modelLabel="Model"
        variantLabel="Variant"
        allowModelGroup
        showVariant
        variantUnsetLabel="Use model default"
        onModelChange={() => {}}
        onVariantChange={() => {}}
      />,
    )
    expect(screen.queryByRole('button', { name: 'Variant' })).not.toBeInTheDocument()
  })

  // 模型组编辑场景不得出现组候选；允许时空值明确表示未设置。
  it('limits model groups and represents an allowed unset value', () => {
    render(
      <ModelSelectionPicker
        models={models}
        modelName=""
        variant=""
        modelLabel="Model"
        variantLabel="Variant"
        allowModelGroup={false}
        showVariant={false}
        allowUnsetModel
        unsetModelLabel="Not set"
        variantUnsetLabel=""
        onModelChange={() => {}}
        onVariantChange={() => {}}
      />,
    )
    fireEvent.click(screen.getByRole('button', { name: 'Model' }))
    expect(screen.getByRole('option', { name: 'Not set' })).toBeInTheDocument()
    expect(screen.queryByRole('option', { name: 'group' })).not.toBeInTheDocument()
  })
})
