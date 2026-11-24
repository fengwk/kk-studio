import { useEffect, useState, useSyncExternalStore, type FormEvent } from 'react'
import { AgentResourceCard } from '@/features/catalog/AiAgentResourceCard'
import { ModelResourceCard } from '@/features/catalog/AiModelResourceCard'
import { ResourceEditorModal } from '@/features/catalog/AiConsoleResourceEditorModal'
import type { ResourceModal } from '@/features/catalog/ai-console-types'
import {
  createAgentEditorPlan,
  editAgentEditorPlan,
  editModelEditorPlan,
} from '@/features/catalog/ai-resource-editor-open-plans'
import { applyEditorPlan, useResourceDeleteConfirm, useResourceDrafts, useResourceFormErrors } from '@/features/catalog/ai-resource-editor-state'
import { buildResourceSubmitPlan } from '@/features/catalog/ai-resource-editor-submit-plans'
import { validateResourceDraft } from '@/features/catalog/ai-resource-form-validation'
import { copyExecutionSettings, createBuiltinExecution, getBuiltinModelSelection } from '@/shared/api/contracts/ai-runtime'
import type { AgentDTO, ProviderDTO, SingleModelDTO, SkillPackageSummaryDTO, ToolCatalogEntryDTO } from '@/shared/api/generated/contracts'
import { useI18n } from '@/shared/i18n'
import { ResourceGrid } from '@/shared/ui/cards/ResourceGrid'
import { CreateCard } from '@/shared/ui/feedback/CreateCard'
import { ConfirmActionModal } from '@/shared/ui/overlays/ConfirmActionModal'
import './catalog-editor-previews.css'

const fixtureMeta = { version: '1', createdAt: 0, updatedAt: 0 }
const providers: ProviderDTO[] = ['openai', 'minimax', 'deepseek', 'anthropic'].map((name) => ({
  ...fixtureMeta, name, providerType: name === 'anthropic' ? 'anthropic' : 'openai', credential: { configured: false },
  modelCallTimeoutMillis: '0', modelCallIdleTimeoutMillis: '0', modelHttpRetryStatusCodes: null,
  promptCacheRetentions: name === 'openai' ? ['NONE', 'SHORT'] : [],
}))

const examplePricing: NonNullable<SingleModelDTO['config']['pricing']> = {
  base: { input: '2', output: '8', cacheRead: '0.2', cacheWrite: '1', cacheWriteByTtl: {}, reasoning: null },
  rules: [{ type: 'time_multiplier', start: '22:00', end: '24:00', timezone: 'UTC', factor: '0.5', inputFactor: '0.25' }],
  changes: [],
}

const initialModels: SingleModelDTO[] = ([
  ['openai', 'gpt-6.1-sol', 'gpt-6.1-sol', 'GPT 6.1 Sol', 272000],
  ['minimax', 'MiniMax-M3.1-Flash', 'MiniMax-M3.1-Flash-Preview', 'MiniMax M3.1 Flash', 400000],
  ['deepseek', 'deepseek-flash', 'deepseek-flash', 'DeepSeek Flash', 300000],
  ['anthropic', 'claude-sonnet', 'claude-sonnet', 'Claude Sonnet', 200000],
] as const).map(([providerName, name, modelId, description, context]) => ({
  ...fixtureMeta, type: 'MODEL', name: `${providerName}/${name}`, modelId, providerName, description,
  config: {
    limit: { context, output: 128000 },
    abilities: {
      tools: true, reasoning: providerName !== 'deepseek', midConversationSystemMessages: providerName === 'anthropic',
      messageLevelEffort: false, inputModalities: ['TEXT', 'IMAGE'],
    },
    pricing: providerName === 'openai' ? examplePricing : null,
    defaultVariant: 'max',
    variants: [
      { id: 'default', promptCacheRetention: 'NONE' },
      { id: 'max', promptCacheRetention: providerName === 'openai' ? 'SHORT' : 'NONE',
        ...(providerName === 'deepseek' ? {} : { reasoningEffort: 'high' }) },
    ],
  },
}))

const toolCatalog: ToolCatalogEntryDTO[] = [
  ['read_file', '读取工作区文件'], ['search_files', '搜索项目中的文本'], ['apply_patch', '修改工作区文件'],
].map(([name, description]) => ({ name, description, provider: { kind: 'BUILTIN', id: 'builtin', name: 'Builtin' } }))

const skills: SkillPackageSummaryDTO[] = [{
  ...fixtureMeta, packageName: 'engineering', packageVersion: '1', publishedAt: 0,
  source: 'GIT', updateAvailable: false,
  skills: [
    { name: 'development', description: '按项目约定实现和验证代码变更' },
    { name: 'code-review', description: '检查改动中的问题与回归风险' },
  ],
}]

const initialAgents: AgentDTO[] = ([
  ['Coder', '实现代码变更并验证结果', 0],
  ['Reviewer', '审查改动并标记回归风险', 1],
  ['Researcher', '阅读资料并整理调查结论', 2],
] as const).map(([name, description, modelIndex]) => ({
  ...fixtureMeta, name, description, systemPrompt: `${description}。`,
  execution: createBuiltinExecution(initialModels[modelIndex].name, null),
  config: {
    tools: name === 'Coder' ? ['read_file', 'search_files', 'apply_patch'] : ['read_file'],
    skills: name === 'Coder' ? [{ packageName: 'engineering', name: 'development' }] : [],
    subagents: name === 'Coder' ? ['Reviewer'] : [],
  },
}))

// Both settings pages see the same example resources until the preview reloads.
let catalog = { agents: initialAgents, models: initialModels }
const listeners = new Set<() => void>()
function subscribe(listener: () => void) {
  listeners.add(listener)
  return () => { listeners.delete(listener) }
}
const getCatalog = () => catalog
function updateCatalog(patch: Partial<typeof catalog>) {
  catalog = { ...catalog, ...patch }
  listeners.forEach((listener) => listener())
}
const unusedDraftChange = () => undefined

function CatalogCardsPreview({ kind }: { kind: 'agent' | 'model' }) {
  const { t, locale } = useI18n()
  const { agents, models } = useSyncExternalStore(subscribe, getCatalog)
  const { deleteConfirm, requestDelete, failDelete, closeDeleteConfirm } = useResourceDeleteConfirm()
  const [modal, setModal] = useState<ResourceModal | null>(null)
  const drafts = useResourceDrafts()
  const errors = useResourceFormErrors(modal)

  useEffect(() => {
    const close = () => { setModal(null); closeDeleteConfirm() }
    document.addEventListener('htmlview:change', close)
    return () => document.removeEventListener('htmlview:change', close)
  }, [closeDeleteConfirm])

  function deleteResource(name: string) {
    requestDelete({
      titleKey: kind === 'agent' ? 'ai.catalog.deleteAgentTitle' : 'ai.catalog.deleteModelTitle',
      descriptionKey: kind === 'agent' ? 'ai.catalog.deleteAgentDescription' : 'ai.catalog.deleteModelDescription',
      name,
      onConfirm: () => {
        const { agents, models } = getCatalog()
        const referencingAgents = agents.filter((agent) => kind === 'agent'
          ? agent.config.subagents?.includes(name)
          : getBuiltinModelSelection(agent.execution)?.modelName === name).map((agent) => agent.name)
        if (referencingAgents.length) {
          const references = referencingAgents.join(', ')
          failDelete(new Error(locale === 'zh-CN'
            ? `不能删除：仍被 Agent ${references} 引用。`
            : `Cannot delete: this resource is in use by ${references}.`))
          return
        }
        if (kind === 'agent') updateCatalog({ agents: agents.filter((agent) => agent.name !== name) })
        else updateCatalog({ models: models.filter((model) => model.name !== name) })
        closeDeleteConfirm()
      },
    })
  }

  function rejectName(key: 'duplicateAgent' | 'duplicateModel') {
    const message = t(`ai.catalog.validation.${key}`)
    errors.setFormError(message)
    errors.setFieldErrors({ name: message })
  }

  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (!modal) return
    const snapshot = drafts.drafts()
    const validation = validateResourceDraft(modal, snapshot)
    if (!validation.ok) {
      errors.setFormError(validation.message)
      errors.setFieldErrors(validation.fields)
      return
    }
    // Use the exact Catalog submit plan; only its dispatch is replaced by an in-memory update.
    const plan = buildResourceSubmitPlan(modal, snapshot)
    const updatedAt = Date.now()
    if (plan.kind === 'agent') {
      if (plan.mode === 'create') {
        if (agents.some((agent) => agent.name === plan.data.name)) return rejectName('duplicateAgent')
        const data = plan.data
        updateCatalog({ agents: [{ ...fixtureMeta, ...data, execution: data.execution ?? null, createdAt: updatedAt, updatedAt }, ...agents] })
      } else {
        const { expectedVersion, ...data } = plan.data
        updateCatalog({ agents: agents.map((agent) => agent.name === plan.name
          ? { ...agent, ...data, execution: data.execution ?? null, version: String(Number(expectedVersion) + 1), updatedAt } : agent) })
      }
    } else if (plan.kind === 'model' && plan.mode === 'edit' && plan.data.type === 'MODEL') {
      const { expectedVersion, ...data } = plan.data
      if (models.some((model) => model.name === data.name && model.name !== plan.name)) return rejectName('duplicateModel')
      updateCatalog({
        models: models.map((model) => model.name === plan.name
          ? { ...model, ...data, version: String(Number(expectedVersion) + 1), updatedAt } : model),
        agents: data.name === plan.name ? agents : agents.map((agent) => {
          const selection = getBuiltinModelSelection(agent.execution)
          if (selection?.modelName !== plan.name || !agent.execution) return agent
          return { ...agent, execution: { ...agent.execution, settings: { ...copyExecutionSettings(agent.execution.settings), modelName: data.name } } }
        }),
      })
    }
    setModal(null)
  }

  return <div className="screen-body">
    <ResourceGrid>
      {kind === 'agent' ? <>
        <CreateCard title={t('ai.catalog.createAgent')} subtitle={t('ai.catalog.createAgentDescription')}
          onClick={() => applyEditorPlan(createAgentEditorPlan(models), setModal, drafts)} />
        {agents.map((agent) => <AgentResourceCard key={agent.name} agent={agent} models={models} deletePending={false}
          onDelete={() => deleteResource(agent.name)}
          onEdit={() => applyEditorPlan(editAgentEditorPlan(agents, models, agent.name), setModal, drafts)} />)}
      </> : models.map((model) => <ModelResourceCard key={model.name} model={model} deletePending={false}
        onDelete={() => deleteResource(model.name)}
        onEdit={() => applyEditorPlan(editModelEditorPlan(models, model.name), setModal, drafts)} />)}
    </ResourceGrid>
    <ConfirmActionModal modal={deleteConfirm} pending={false} onClose={closeDeleteConfirm} />
    <ResourceEditorModal
      modal={modal} providers={providers} models={models} agents={agents} toolCatalog={toolCatalog} skills={skills}
      {...drafts.drafts()} pending={false} formError={errors.formError} fieldErrors={errors.fieldErrors}
      onProviderDraftChange={unusedDraftChange} onModelGroupDraftChange={unusedDraftChange} onModelCreateKindChange={unusedDraftChange}
      onAgentDraftChange={(draft) => { errors.clear(); drafts.setAgentDraft(draft) }}
      onModelDraftChange={(draft) => { errors.clear(); drafts.setModelDraft(draft) }}
      onClose={() => setModal(null)} onSubmit={submit}
    />
  </div>
}

export function AgentCardsPreview() {
  return <CatalogCardsPreview kind="agent" />
}

export function ModelCardsPreview() {
  return <CatalogCardsPreview kind="model" />
}
