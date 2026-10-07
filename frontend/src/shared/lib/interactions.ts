import type {
  InteractionDTO,
  ManualInteractionDTO,
} from '@/shared/api/contracts/ai-interaction'

/**
 * 待处理条目的稳定身份：环境等待是聚合项（多个调用的 `interactionId` 都是 null），
 * 只能按 `(真实执行根, 冻结环境)` 去重，否则会把这些 null 主键错误合并成一项。
 */
export function interactionIdentity(item: InteractionDTO): string {
  return item.type === 'ENVIRONMENT_WAIT'
    ? `environment:${item.rootThreadId}:${item.environmentId}`
    : `invocation:${item.interactionId}`
}

/**
 * 只保留可操作的人工等待。环境等待是 `(真实执行根, 所需环境)` 聚合，没有可回写的调用，
 * 只在全局待处理页与工具状态行呈现；根面板据此不再重复渲染第二张环境提醒卡。
 */
export function manualInteractions(items: InteractionDTO[]): ManualInteractionDTO[] {
  return items.filter((item): item is ManualInteractionDTO => item.type !== 'ENVIRONMENT_WAIT')
}
