import { createContext, useContext } from 'react'

/** Chat/Project 入口传给 IDE 宿主的最小环境描述；null 表示让面板显示环境选择。 */
export interface IdeEnvironmentInfo {
  id: string
  name: string
  ready: boolean
}

export type IdeActionMode = 'files' | 'shell'

export interface IdeOpenEnvironmentRequest {
  environment: IdeEnvironmentInfo | null
  /** files 只聚焦/打开工作台；shell 在新视图可用后再请求该视图的终端。 */
  mode?: IdeActionMode
}

export interface IdePanelContextValue {
  openEnvironment: (request: IdeOpenEnvironmentRequest) => void
  panelVisible: boolean
  togglePanel: (environment: IdeEnvironmentInfo) => void
  /** Chat 详情页激活时匹配其 Environment；没有已打开 view 不自动创建。 */
  activateChatEnvironment: (environment: IdeEnvironmentInfo | null) => void
}

export const IdePanelContext = createContext<IdePanelContextValue | null>(null)

/** Chat 宿主必须提供 IDE 面板；缺少 Provider 属于装配错误。 */
export function useIdePanel(): IdePanelContextValue {
  const context = useContext(IdePanelContext)
  if (context == null) {
    throw new Error('IdePanelProvider is required')
  }
  return context
}

/** Chat pane 在独立测试或非 Chat 宿主中不存在面板；生产由 ChatIdeWorkspace 提供。 */
export function useOptionalIdePanel(): IdePanelContextValue | null {
  return useContext(IdePanelContext)
}
