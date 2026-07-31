import type { ReactNode } from 'react'
import {
  SearchField,
  StateBlock,
} from '@/shared/ui/console/AiConsoleCommonCards'
import { NavigationSlot } from '@/platform/workbench/WorkbenchSlots'

export function AiConsoleFrame({
  search,
  onSearchChange,
  busy,
  error,
  mutationError,
  content,
  children,
}: {
  search: string
  onSearchChange: (value: string) => void
  busy: boolean
  error: unknown
  mutationError: Error | null
  content: ReactNode
  children?: ReactNode
}) {
  return (
    <section className="screen active">
      <nav className="subbar">
        <NavigationSlot />
        <SearchField value={search} onChange={onSearchChange} />
      </nav>
      <div className="screen-body">
        {busy && <StateBlock title="正在加载资源" />}
        {Boolean(error) && (
          <StateBlock
            title={error instanceof Error ? error.message : '资源加载失败'}
            tone="danger"
          />
        )}
        {mutationError && (
          <StateBlock
            title={mutationError.message || '操作失败，请稍后重试'}
            tone="danger"
          />
        )}
        {!busy && !error && content}
      </div>
      {children}
    </section>
  )
}
