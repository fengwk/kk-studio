/* eslint-disable react-refresh/only-export-components */
import { createContext, useContext, type PropsWithChildren, type ReactNode } from 'react'
import {
  SearchField,
  StateBlock,
} from '@/shared/ui/console/AiConsoleCommonCards'
import {
  useAiConsoleController,
  type AiConsolePageScope,
} from '@/features/ai/extensions/useAiConsoleController'
import type { ExtensionComponentProps } from '@/platform/extensions/types'
import { NavigationSlot } from '@/platform/workbench/WorkbenchSlots'

export type AiConsoleController = ReturnType<typeof useAiConsoleController>

const AiConsoleContext = createContext<AiConsoleController | null>(null)

export function AiConsoleRuntime({
  scope,
  children,
}: PropsWithChildren<{ scope: AiConsolePageScope }>) {
  const controller = useAiConsoleController(scope)
  return <AiConsoleContext.Provider value={controller}>{children}</AiConsoleContext.Provider>
}

export function useAiConsole() {
  const controller = useContext(AiConsoleContext)
  if (!controller) {
    throw new Error('AiConsoleRuntime is required')
  }
  return controller
}

export function useOptionalAiConsole() {
  return useContext(AiConsoleContext)
}

export function AiConsoleFrame({
  content,
  children,
}: ExtensionComponentProps & { content: ReactNode }) {
  const controller = useAiConsole()
  return (
    <section className="screen active">
      <nav className="subbar">
        <NavigationSlot />
        <SearchField value={controller.search} onChange={controller.setSearch} />
      </nav>
      <div className="screen-body">
        {controller.busy && <StateBlock title="正在加载资源" />}
        {controller.error && (
          <StateBlock
            title={
              controller.error instanceof Error
                ? controller.error.message
                : '资源加载失败'
            }
            tone="danger"
          />
        )}
        {controller.mutationError && (
          <StateBlock
            title={
              controller.mutationError instanceof Error
                ? // 外层仅展示无模态时的操作错误；文案已在 controller 侧尽量友好
                  controller.mutationError.message
                : '操作失败，请稍后重试'
            }
            tone="danger"
          />
        )}
        {!controller.busy && !controller.error && content}
      </div>
      {children}
    </section>
  )
}
