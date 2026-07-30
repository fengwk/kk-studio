export { AgentResourceCard } from '@/features/ai/catalog/AiAgentResourceCard'
export { ConfirmActionModal } from '@/features/ai/shared/ConfirmActionModal'
export {
  AgentsPanel,
  ModelsPanel,
  ProvidersPanel,
} from '@/features/ai/catalog/AiConsolePanels'
export { ResourceEditorModal } from '@/features/ai/catalog/AiConsoleResourceEditorModal'
export {
  extractContextWindow,
  extractDefaultVariantFromModel,
} from '@/features/ai/catalog/ai-resource-draft-codecs'
export {
  formatModelRef,
  modelRef,
  toAgentModelViews,
  type AgentModelView,
} from '@/features/ai/catalog/AgentModelView'
export { variantOptionsFromModel } from '@/features/ai/catalog/ai-draft-variant-options'
export { useAiConsoleResourceController } from '@/features/ai/catalog/useAiConsoleResourceController'
