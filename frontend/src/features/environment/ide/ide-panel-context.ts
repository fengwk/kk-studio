import { createContext, useContext } from 'react'

/** Chat/Project 入口传给全局面板的最小环境描述；null 表示让面板显示环境选择。 */
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
}

export const IdePanelContext = createContext<IdePanelContextValue | null>(null)

/** 应用外壳必须提供全局面板；缺少 Provider 属于装配错误。 */
export function useIdePanel(): IdePanelContextValue {
  const context = useContext(IdePanelContext)
  if (context == null) {
    throw new Error('IdePanelProvider is required')
  }
  return context
}

/** Chat pane 在独立测试或嵌入式宿主中可不存在全局面板；生产由 AppProviders 提供。 */
export function useOptionalIdePanel(): IdePanelContextValue | null {
  return useContext(IdePanelContext)
}
