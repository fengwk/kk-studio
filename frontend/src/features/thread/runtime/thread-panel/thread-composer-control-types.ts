import type { ModelDTO } from '@/shared/api/generated/contracts'

/**
 * Composer 底栏设置菜单的输入契约。
 *
 * 单独成模块是因为 Controls、三个触发按钮与菜单生命周期 hook 都要读它；
 * 放在组件文件里会让「组件文件只导出组件」的约束被类型导出打破。
 */
export interface ThreadComposerModelSelection {
  /** 全局唯一逻辑模型名（单 Model 或组）。 */
  modelName: string
  /** 单 Model 的可选 Variant；组为空。 */
  variant?: string | null
}

export interface ThreadComposerModelOption {
  name: string
  type: ModelDTO['type']
  config?: {
    defaultVariant?: string | null
    variants?: ReadonlyArray<{ id: string }>
  } | null
}

interface ThreadComposerEnvironmentOption {
  name: string
}

/**
 * 设置选择的状态：draft=本地草稿，将随下一条消息一起生效；pending=已提交，等待安全点生效；
 * null=无需要展示的差异（已生效或未编辑）。applied 不做常驻提示。
 */
type ThreadComposerSettingsStatus = 'draft' | 'pending' | null

export interface ThreadComposerSettingsInput {
  modelSelection: ThreadComposerModelSelection | null
  models: readonly ThreadComposerModelOption[]
  yoloEnabled: boolean
  environmentName: string | null
  environments: readonly ThreadComposerEnvironmentOption[]
  status?: ThreadComposerSettingsStatus
  /** 执行根有正在运行的子代理时的环境切换提示；由宿主按执行树决定，不在 Composer 内推断。 */
  environmentSwitchNotice?: string | null
  onModelChange: (model: ThreadComposerModelSelection) => void
  onYoloChange: (enabled: boolean) => void
  onEnvironmentChange: (environmentName: string | null) => void
}

export type ThreadComposerControlMenu = 'permission' | 'environment' | 'model' | 'variant' | null
