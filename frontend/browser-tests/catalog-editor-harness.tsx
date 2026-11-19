import { useState } from 'react'
import { createRoot } from 'react-dom/client'
import '@/styles.css'
import { ResourceEditorModal } from '@/features/catalog/AiConsoleResourceEditorModal'
import { emptyAgentDraft } from '@/features/catalog/ai-agent-draft-codec'
import { emptyModelDraft } from '@/features/catalog/ai-model-draft-codec'
import { emptyModelPricingDraft } from '@/features/catalog/model-pricing-draft'
import { emptyModelGroupDraft } from '@/features/catalog/ai-model-group-draft-codec'
import { emptyProviderDraft } from '@/features/catalog/ai-provider-draft-codec'
import type { ModelDraft } from '@/features/catalog/ai-console-types'
import type { ProviderDTO } from '@/shared/api/generated/contracts'
import { setLocale } from '@/shared/i18n'

setLocale('zh-CN')

const SAMPLE_PROVIDER: ProviderDTO = {
  name: 'openai',
  description: 'OpenAI 兼容端点',
  providerType: 'openai',
  promptCacheRetentions: ['NONE', 'SHORT', 'LONG'],
  baseUrl: 'https://api.example.com/v1',
  credential: { configured: true },
  modelCallTimeoutMillis: '1800000',
  modelCallIdleTimeoutMillis: '120000',
  modelHttpRetryStatusCodes: null,
  version: '1',
  createdAt: 0,
  updatedAt: 0,
}

/** Shows the three pricing groups, TTL controls and mobile layout in a real browser fixture. */
export function CatalogEditorHarness() {
  const [modelDraft, setModelDraft] = useState<ModelDraft>(() => {
    const pricing = emptyModelPricingDraft()
    pricing.definition.base = {
      input: '1', output: '2', cacheRead: '0.1', cacheWrite: '0.2',
      cacheWriteByTtl: { '300': '0.2' }, reasoning: null,
    }
    pricing.definition.inputTokenRanges = [{
      draftKey: 'range-1', minInputTokens: '200000', maxInputTokensExclusive: '',
      rates: { input: '0.5' }, cacheWriteTtlMode: 'inherit', cacheWriteByTtl: {},
    }]
    pricing.definition.serviceTiers = [{
      draftKey: 'tier-1', serviceTier: 'priority',
      factors: { factor: '1', inputFactor: '1.25', outputFactor: '', cacheReadFactor: '', cacheWriteFactor: '', reasoningFactor: '' },
    }]
    pricing.definition.timePeriods = [{
      draftKey: 'time-1', weekdays: [1, 2, 3, 4, 5], start: '01:00', end: '04:00', timezone: 'UTC',
      factors: { factor: '0.8', inputFactor: '', outputFactor: '', cacheReadFactor: '', cacheWriteFactor: '', reasoningFactor: '' },
    }]
    return {
      ...emptyModelDraft(SAMPLE_PROVIDER),
      name: 'demo-model',
      modelId: 'demo-wire-model',
      pricing,
    }
  })

  return (
    <ResourceEditorModal
      modal={{ kind: 'model', mode: 'create', resourceKind: 'MODEL' }}
      providers={[SAMPLE_PROVIDER]}
      models={[]}
      providerDraft={emptyProviderDraft()}
      modelDraft={modelDraft}
      modelGroupDraft={emptyModelGroupDraft()}
      agentDraft={emptyAgentDraft()}
      pending={false}
      onClose={() => undefined}
      onProviderDraftChange={() => undefined}
      onModelDraftChange={setModelDraft}
      onModelGroupDraftChange={() => undefined}
      onModelCreateKindChange={() => undefined}
      onAgentDraftChange={() => undefined}
      onSubmit={(event) => event.preventDefault()}
    />
  )
}

const rootEl = document.getElementById('root')
if (rootEl) {
  createRoot(rootEl).render(<CatalogEditorHarness />)
}
