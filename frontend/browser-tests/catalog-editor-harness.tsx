import { useState } from 'react'
import { createRoot } from 'react-dom/client'
import '@/styles.css'
import { ResourceEditorModal } from '@/features/catalog/AiConsoleResourceEditorModal'
import { emptyAgentDraft } from '@/features/catalog/ai-agent-draft-codec'
import { emptyModelDraft } from '@/features/catalog/ai-model-draft-codec'
import { emptyModelGroupDraft } from '@/features/catalog/ai-model-group-draft-codec'
import { emptyProviderDraft } from '@/features/catalog/ai-provider-draft-codec'
import type { ModelDraft } from '@/features/catalog/ai-console-types'
import type { AgentProviderDTO } from '@/shared/api/generated/contracts'
import { setLocale } from '@/shared/i18n'

setLocale('zh-CN')

const SAMPLE_PROVIDER: AgentProviderDTO = {
  name: 'openai',
  description: 'OpenAI 兼容端点',
  providerType: 'openai',
  baseUrl: 'https://api.example.com/v1',
  configured: true,
  modelCallTimeoutMillis: '1800000',
  modelCallIdleTimeoutMillis: '120000',
  modelHttpRetryStatusCodes: null,
  version: '1',
  createTime: 0,
  updateTime: 0,
}

/** 打开一个覆盖折叠规则、TTL 与移动端触区的完整 Catalog 编辑器。 */
export function CatalogEditorHarness() {
  const [modelDraft, setModelDraft] = useState<ModelDraft>(() => ({
    ...emptyModelDraft(SAMPLE_PROVIDER),
    name: 'demo-model',
    modelId: 'demo-wire-model',
    pricing: {
      base: {
        input: '1',
        output: '2',
        cacheRead: '0.1',
        cacheWrite: '0.2',
        cacheWriteByTtl: { '300': '0.2' },
        reasoning: null,
      },
      overrides: [{ id: 'override-1', when: {}, rates: { input: '0.5' } }],
      multipliers: [{ id: 'multiplier-1', when: {}, factors: { input: '1.25' } }],
    },
  }))

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
