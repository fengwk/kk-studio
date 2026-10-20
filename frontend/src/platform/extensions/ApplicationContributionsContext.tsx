/* eslint-disable react-refresh/only-export-components */
import { createContext, useContext, type PropsWithChildren } from 'react'
import type { ApplicationContributions } from '@/platform/extensions/types'

const ApplicationContributionsContext = createContext<ApplicationContributions | null>(null)

/** 注入组合根产出的只读表；测试可传入独立静态 fixture。 */
export function ApplicationContributionsProvider({
  contributions,
  children,
}: PropsWithChildren<{ contributions: ApplicationContributions }>) {
  return (
    <ApplicationContributionsContext.Provider value={contributions}>
      {children}
    </ApplicationContributionsContext.Provider>
  )
}

export function useApplicationContributions(): ApplicationContributions {
  const contributions = useContext(ApplicationContributionsContext)
  if (!contributions) {
    throw new Error('ApplicationContributionsProvider is required')
  }
  return contributions
}

/** 允许可复用消息组件在测试或非 Workbench 宿主中回退到默认 renderer。 */
export function useOptionalApplicationContributions(): ApplicationContributions | null {
  return useContext(ApplicationContributionsContext)
}
