import { useState } from 'react'
import {
  createAgentEditorPlan,
  createModelEditorPlan,
  createProviderEditorPlan,
  editAgentEditorPlan,
  editModelEditorPlan,
  editProviderEditorPlan,
} from '@/features/catalog/ai-resource-editor-open-plans'
import type { ResourceModal } from '@/features/catalog/ai-console-types'
import { applyEditorPlan, useResourceDrafts } from '@/features/catalog/ai-resource-editor-state'
import type { AgentDTO, ModelDTO, ProviderDTO } from '@/shared/api/generated/contracts'

/**
 * 资源编辑器的打开面：把「哪个资源、哪种模式」翻译成打开计划，
 * 计划里的弹窗与草稿一次性替换当前编辑器；目标已不存在时计划为 null，编辑器保持原状。
 */
export function useResourceEditorNavigation() {
  const [resourceModal, setResourceModal] = useState<ResourceModal | null>(null)
  const drafts = useResourceDrafts()

  function open(plan: Parameters<typeof applyEditorPlan>[0]) {
    applyEditorPlan(plan, setResourceModal, drafts)
  }

  return {
    resourceModal,
    drafts,
    closeResourceModal: () => setResourceModal(null),
    /** 仅创建态切换 Model/Group Tab：两份草稿都保留，只改弹窗的资源种类。 */
    selectModelCreateKind: (resourceKind: 'MODEL' | 'GROUP') =>
      setResourceModal((current) =>
        current && current.kind === 'model' && current.mode === 'create'
          ? { ...current, resourceKind }
          : current,
      ),
    createProvider: () => open(createProviderEditorPlan()),
    editProvider: (providers: ProviderDTO[], providerName: string) =>
      open(editProviderEditorPlan(providers, providerName)),
    createModel: (providers: ProviderDTO[]) => open(createModelEditorPlan(providers)),
    editModel: (models: ModelDTO[], modelName: string) =>
      open(editModelEditorPlan(models, modelName)),
    createAgent: (models: ModelDTO[]) => open(createAgentEditorPlan(models)),
    editAgent: (agents: AgentDTO[], models: ModelDTO[], agentName: string) =>
      open(editAgentEditorPlan(agents, models, agentName)),
  }
}
