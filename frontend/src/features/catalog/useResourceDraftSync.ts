import { useEffect } from 'react'
import { useI18n } from '@/shared/i18n'
import { toUserFacingErrorMessage } from '@/shared/lib/user-facing-error'
import {
  normalizeAgentDraftDefaultVariant,
  normalizeCreateAgentDraftSelection,
  normalizeCreateModelDraftProvider,
  normalizeEditModelDraftProvider,
  normalizeModelDraftDefaultVariant,
} from '@/features/catalog/ai-draft-normalizers'
import type { AgentDraft, ModelDraft, ResourceModal } from '@/features/catalog/ai-console-types'
import type { ModelDTO, ProviderDTO } from '@/shared/api/generated/contracts'

/**
 * 编辑器打开期间同步草稿与当前资源集合：
 * - 模型：Provider 可能已下线（edit）或还没选（create），默认变体必须落在可选范围内；
 * - Agent：默认模型/变体同理。
 *
 * 只在编辑器打开时生效；资源集合刷新不会改动已关闭的草稿。
 */
export function useDraftResourceSync(options: {
  modal: ResourceModal | null
  providers: ProviderDTO[]
  models: ModelDTO[]
  setModelDraft: (update: (draft: ModelDraft) => ModelDraft) => void
  setAgentDraft: (update: (draft: AgentDraft) => AgentDraft) => void
}) {
  const { modal, providers, models, setModelDraft, setAgentDraft } = options

  useEffect(() => {
    if (!modal) {
      return
    }
    if (modal.kind === 'model') {
      setModelDraft((currentDraft) => {
        const normalizedProvider =
          modal.mode === 'edit'
            ? normalizeEditModelDraftProvider(currentDraft, currentDraft.providerName)
            : normalizeCreateModelDraftProvider(currentDraft, providers)
        return normalizeModelDraftDefaultVariant(normalizedProvider)
      })
      return
    }
    if (modal.kind === 'agent') {
      setAgentDraft((currentDraft) =>
        modal.mode === 'edit'
          ? normalizeAgentDraftDefaultVariant(currentDraft, models)
          : normalizeCreateAgentDraftSelection(currentDraft, models),
      )
    }
  }, [models, providers, modal, setModelDraft, setAgentDraft])
}

/**
 * 把资源写入失败转成用户可读文案并落到编辑器：只显示在模态内，
 * 依赖 locale 以便语言切换后重新渲染已存在的错误。
 */
export function useResourceMutationError(options: {
  modal: ResourceModal | null
  mutationError: unknown
  showFailure: (message: string) => void
}) {
  const { modal, mutationError, showFailure } = options
  const { locale } = useI18n()

  useEffect(() => {
    if (!modal || !mutationError) {
      return
    }
    showFailure(toUserFacingErrorMessage(mutationError))
  }, [modal, mutationError, locale, showFailure])
}
