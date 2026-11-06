import { translate, type TranslationKey } from '@/shared/i18n'
import type {
  InteractionApproval,
  InteractionDTO,
  InteractionInput,
  InteractionOwnerDTO,
} from '@/shared/api/generated/contracts'

type Translate = typeof translate

/** 会话归属的产品类型标签；新增 kind 必须同时提供标签，未知 kind 原样显示不猜测。 */
const OWNER_KIND_KEYS = {
  CHAT: 'ai.interaction.ownerKind.CHAT',
  ISSUE_AGENT: 'ai.interaction.ownerKind.ISSUE_AGENT',
  AGENT_TASK: 'ai.interaction.ownerKind.AGENT_TASK',
} as const satisfies Record<string, TranslationKey>

/** 归属显示的类型与标题分隔符。 */
const OWNER_LABEL_SEPARATOR = ' · '

/**
 * 交互待办的归属显示：固定为“<类型> · <标题>”。
 *
 * 标题来自会话归属 SPI 的展示投影，缺失时只显示类型，不用 id 或来源 Thread 冒充标题。
 */
export function interactionOwnerLabel(
  owner: InteractionOwnerDTO,
  t: Translate = translate,
): string {
  const kindKey = OWNER_KIND_KEYS[owner.kind as keyof typeof OWNER_KIND_KEYS]
  const kind = kindKey == null ? owner.kind : t(kindKey)
  const title = owner.title?.trim()
  return title ? `${kind}${OWNER_LABEL_SEPARATOR}${title}` : kind
}

/**
 * 归属跳转地址：只有带产品页面的归属才返回应用内路径。
 *
 * AGENT_TASK 没有产品页面，即使服务端给了 link 也不跳转；其他来源必须是以单个 `/` 开头的
 * 应用内路径，协议相对地址、反斜杠与控制字符一律不采纳。
 */
export function interactionOwnerLink(owner: InteractionOwnerDTO): string | null {
  const link = owner.link
  if (owner.kind === 'AGENT_TASK' || link == null) {
    return null
  }
  if (!/^\/(?!\/)/.test(link) || link.includes('\\') || /\p{Cc}/u.test(link)) {
    return null
  }
  return link
}

/**
 * 待处理条目的稳定身份：环境等待是聚合项，判别联合不为它声明调用主键，
 * 只能按 `(真实执行根, 冻结环境)` 去重，否则会把不同环境错误合并成一项。
 */
export function interactionIdentity(item: InteractionDTO): string {
  return item.type === 'ENVIRONMENT_WAIT'
    ? `environment:${item.rootThreadId}:${item.environmentId}`
    : `invocation:${item.toolInvocationId}`
}

/**
 * 只保留可操作的人工等待。环境等待是 `(真实执行根, 所需环境)` 聚合，没有可回写的调用，
 * 只在全局待处理页与工具状态行呈现；根面板据此不再重复渲染第二张环境提醒卡。
 */
export function manualInteractions(
  items: InteractionDTO[],
): (InteractionInput | InteractionApproval)[] {
  return items.filter(
    (item): item is InteractionInput | InteractionApproval =>
      item.type !== 'ENVIRONMENT_WAIT',
  )
}
