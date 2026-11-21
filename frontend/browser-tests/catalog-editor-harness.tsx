import { useState, type FormEvent } from 'react'
import { createRoot } from 'react-dom/client'
import '@/styles.css'
import { ResourceEditorModal } from '@/features/catalog/AiConsoleResourceEditorModal'
import type { CapabilityDirectory } from '@/features/catalog/CapabilitySelectionPanels'
import { emptyAgentDraft } from '@/features/catalog/ai-agent-draft-codec'
import { emptyModelDraft } from '@/features/catalog/ai-model-draft-codec'
import { createPricingDraftKey, emptyModelPricingDraft } from '@/features/catalog/model-pricing-draft'
import { emptyModelGroupDraft } from '@/features/catalog/ai-model-group-draft-codec'
import { emptyProviderDraft } from '@/features/catalog/ai-provider-draft-codec'
import type { ResourceFieldKey } from '@/features/catalog/ai-resource-form-validation'
import type { ModelDraft } from '@/features/catalog/ai-console-types'
import type {
  AgentDTO,
  ProviderDTO,
  SkillPackageSummaryDTO,
  ToolCatalogEntryDTO,
} from '@/shared/api/generated/contracts'
import { setLocale } from '@/shared/i18n'
import { FIXTURE_MODELS } from './model-browser-fixtures'

// 语言由查询串决定：默认 zh-CN，`locale=en-US` 用于校验英文界面文案。
setLocale(new URLSearchParams(window.location.search).get('locale') === 'en-US' ? 'en-US' : 'zh-CN')

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

/** 离线工具目录：两个 provider kind，验证分组标题与搜索都基于真实候选。 */
const SAMPLE_TOOLS: ToolCatalogEntryDTO[] = [
  { name: 'read', description: 'Read workspace files', provider: { kind: 'BUILTIN', id: 'builtin', name: 'Built-in' } },
  { name: 'web_search', description: 'Search the public web', provider: { kind: 'MCP', id: 'search', name: 'Search MCP' } },
]

/** 离线技能包：一个包内两个技能，选中值键为 `packageName:name`。 */
const SAMPLE_SKILLS: SkillPackageSummaryDTO[] = [
  {
    packageName: 'engineering',
    description: null,
    packageVersion: 'fixture',
    publishedAt: 0,
    skills: [
      { name: 'review', description: 'Review code changes' },
      { name: 'deploy', description: 'Ship to an environment' },
    ],
    source: 'GIT',
    updateAvailable: false,
    version: '1',
    createdAt: 0,
    updatedAt: 0,
  },
]

/** 离线子代理目录：来源是全局 Agent 定义。 */
const SAMPLE_AGENTS: AgentDTO[] = [
  {
    name: 'reviewer',
    description: 'Reviews pull requests',
    systemPrompt: null,
    execution: null,
    config: { tools: [], skills: [], subagents: [] },
    version: '1',
    createdAt: 0,
    updatedAt: 0,
  },
]

interface SubmitErrors {
  fieldErrors: Partial<Record<ResourceFieldKey, string>>
  formError: string
}

/**
 * 受控提交后注入的校验错误，文案与真实校验一致：
 * `price`/`skills`/`general` 的 banner 与字段错误同文，用来验证「与内联错误同文不再重复展示」。
 */
const ERROR_FIXTURES: Record<string, SubmitErrors> = {
  preset: {
    fieldErrors: { variants: '预设 ID 不能重复' },
    formError: '',
  },
  price: {
    fieldErrors: { pricing: '请检查价格：填写 0 或正数即可' },
    formError: '请检查价格：填写 0 或正数即可',
  },
  general: {
    fieldErrors: { general: '保存失败，请检查表单后重试' },
    formError: '保存失败，请检查表单后重试',
  },
  skills: {
    fieldErrors: { skills: 'Skills 名称冲突，请检查勾选项' },
    formError: 'Skills 名称冲突，请检查勾选项',
  },
}

interface HarnessScenario {
  kind: 'model' | 'agent'
  errors: SubmitErrors | null
  /** 技能目录查询失败：面板必须呈现失败与重试，而不是「目录为空」。 */
  skillsDirectoryError: boolean
}

/** 场景全部由查询串决定：默认单模型创建弹窗，`scenario=agent` 切到 Agent，`errors=` 注入提交错误。 */
function readScenario(): HarnessScenario {
  const params = new URLSearchParams(window.location.search)
  const errorKey = params.get('errors') ?? ''
  return {
    kind: params.get('scenario') === 'agent' ? 'agent' : 'model',
    errors: ERROR_FIXTURES[errorKey] ?? null,
    skillsDirectoryError: params.get('directories') === 'error',
  }
}

/** 展示三种定价分组、TTL 控件、桌面区块导航与窄屏布局的真实浏览器 fixture。 */
export function CatalogEditorHarness() {
  const [scenario] = useState<HarnessScenario>(readScenario)
  const [submissions, setSubmissions] = useState(0)
  const [retries, setRetries] = useState(0)
  const [formError, setFormError] = useState('')
  const [fieldErrors, setFieldErrors] = useState<Partial<Record<ResourceFieldKey, string>>>({})
  const [modelDraft, setModelDraft] = useState<ModelDraft>(() => {
    const pricing = emptyModelPricingDraft()
    pricing.definition.base = {
      input: '1', output: '2', cacheRead: '0.1', cacheWrite: '0.2',
      cacheWriteByTtl: [{ draftKey: createPricingDraftKey(), seconds: '300', rate: '0.2' }], reasoning: null,
    }
    pricing.definition.inputTokenRanges = [{
      draftKey: 'range-1', minInputTokens: '200000', maxInputTokensExclusive: '',
      rates: { input: '0.5' }, cacheWriteTtlMode: 'inherit', cacheWriteByTtl: [],
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
  const [agentDraft, setAgentDraft] = useState(() => emptyAgentDraft())

  const readyDirectory: CapabilityDirectory = { status: 'ready', retry: () => undefined }
  const skillsDirectory: CapabilityDirectory = scenario.skillsDirectoryError
    ? { status: 'error', retry: () => setRetries((current) => current + 1) }
    : readyDirectory

  /** 受控提交：只记录次数并注入本场景的错误，绝不发起真实请求或跳转。 */
  function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    setSubmissions((current) => current + 1)
    setFieldErrors(scenario.errors?.fieldErrors ?? {})
    setFormError(scenario.errors?.formError ?? '')
  }

  return (
    <>
      <div
        data-testid="harness-state"
        data-submit-count={submissions}
        data-retry-count={retries}
        hidden
      />
      <ResourceEditorModal
        modal={
          scenario.kind === 'agent'
            ? { kind: 'agent', mode: 'create' }
            : { kind: 'model', mode: 'create', resourceKind: 'MODEL' }
        }
        providers={[SAMPLE_PROVIDER]}
        models={FIXTURE_MODELS}
        agents={SAMPLE_AGENTS}
        toolCatalog={SAMPLE_TOOLS}
        skills={SAMPLE_SKILLS}
        capabilityDirectories={
          scenario.kind === 'agent'
            ? { tools: readyDirectory, skills: skillsDirectory, subagents: readyDirectory }
            : undefined
        }
        providerDraft={emptyProviderDraft()}
        modelDraft={modelDraft}
        modelGroupDraft={emptyModelGroupDraft()}
        agentDraft={agentDraft}
        pending={false}
        formError={formError}
        fieldErrors={fieldErrors}
        onClose={() => undefined}
        onProviderDraftChange={() => undefined}
        onModelDraftChange={setModelDraft}
        onModelGroupDraftChange={() => undefined}
        onModelCreateKindChange={() => undefined}
        onAgentDraftChange={setAgentDraft}
        onSubmit={handleSubmit}
      />
    </>
  )
}

const rootEl = document.getElementById('root')
if (rootEl) {
  createRoot(rootEl).render(<CatalogEditorHarness />)
}
