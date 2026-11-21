import { useEffect, useMemo, useRef } from 'react'
import { useOptionalIdePanel } from '@/features/environment/ide/ide-panel-context'
import { useI18n } from '@/shared/i18n'
import { isCanonicalUuid } from '@/shared/lib/uuid'
import type { ChatDTO, EnvironmentDTO } from '@/shared/api/generated/contracts'

export interface ChatIdeToggle {
  hint: string
  hintId: string
  /** Chat 环境不可用或本页面没有 IDE 面板时禁用开关。 */
  disabled: boolean
  /** Chat 的默认环境当前不可用：开关的无障碍描述需要说明原因。 */
  environmentUnavailable: boolean
  panelVisible: boolean
  toggle: () => void
}

/**
 * Chat 顶栏的 IDE 开关：Chat 的默认环境决定可打开哪个 IDE 面板。
 *
 * 环境事实变化时激活对应面板；环境名不可用或 id 不是规范 UUID 时开关禁用，并在
 * 无障碍描述里说明原因。
 */
export function useChatIdeToggle(
  chat: ChatDTO,
  environments: EnvironmentDTO[],
  hintId: string,
): ChatIdeToggle {
  const { t } = useI18n()
  const idePanel = useOptionalIdePanel()
  const chatEnvironment = environments.find((environment) => environment.name === chat.environmentName)
  // 只按环境事实（id、名称、就绪）变化重建，列表回读带来的新对象不得重复激活面板。
  const environmentId = chatEnvironment && isCanonicalUuid(chatEnvironment.id) ? chatEnvironment.id : null
  const environmentName = chatEnvironment?.name
  const environmentReady = chatEnvironment?.ready === true
  const ideEnvironment = useMemo(() => (
    environmentId != null && environmentName != null
      ? { id: environmentId, name: environmentName, ready: environmentReady }
      : null
  ), [environmentId, environmentName, environmentReady])
  const hint = ideEnvironment != null
    ? t('ai.ide.toggle')
    : t(chat.environmentName ? 'ai.ide.chatEnvironmentUnavailable' : 'ai.ide.selectChatEnvironment')
  const activatedIdeEnvironmentRef = useRef<string | null | undefined>(undefined)
  useEffect(() => {
    const key = ideEnvironment == null
      ? null
      : `${ideEnvironment.id}:${ideEnvironment.name}:${String(ideEnvironment.ready)}`
    if (activatedIdeEnvironmentRef.current === key) {
      return
    }
    activatedIdeEnvironmentRef.current = key
    idePanel?.activateChatEnvironment(ideEnvironment)
  }, [ideEnvironment, idePanel])

  return {
    hint,
    hintId,
    disabled: ideEnvironment == null || idePanel == null,
    environmentUnavailable: ideEnvironment == null,
    panelVisible: idePanel?.panelVisible ?? false,
    toggle: () => {
      if (ideEnvironment != null) {
        idePanel?.togglePanel(ideEnvironment)
      }
    },
  }
}
