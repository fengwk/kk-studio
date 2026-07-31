import { type ReactNode } from 'react'
import { Link } from 'react-router'
import { SearchField, StateBlock } from '@/shared/ui/console/AiConsoleCommonCards'
import { ComfyuiRunModal } from '@/features/comfyui/ComfyuiRunModal'
import { ComfyuiRuntime, useOptionalComfyui } from '@/features/comfyui/ComfyuiRuntime'
import { ComfyuiWorkflowsPanel } from '@/features/comfyui/ComfyuiWorkflowsPanel'
import type { ExtensionComponentProps } from '@/platform/extensions/types'

export function ComfyuiPage({ children }: ExtensionComponentProps) {
  return (
    <ComfyuiRuntime>
      <ComfyuiFrame content={<ComfyuiPanel />}>{children}</ComfyuiFrame>
      <ComfyuiRunModalHost />
    </ComfyuiRuntime>
  )
}

function ComfyuiFrame({
  content,
  children,
}: ExtensionComponentProps & { content: ReactNode }) {
  const controller = useOptionalComfyui()
  if (!controller) {
    throw new Error('ComfyuiRuntime is required')
  }
  return (
    <section className="screen active">
      <nav className="subbar">
        <nav className="subnav" aria-label="Tools">
          <Link className="active" to="/comfyui">
            ComfyUI
          </Link>
        </nav>
        <SearchField value={controller.search} onChange={controller.setSearch} />
      </nav>
      <div className="screen-body">
        {controller.busy && <StateBlock title="正在加载 ComfyUI 工作流" />}
        {controller.error && (
          <StateBlock
            title={
              controller.error instanceof Error
                ? controller.error.message
                : '工作流加载失败'
            }
            tone="danger"
          />
        )}
        {controller.mutationError && (
          <StateBlock
            title={
              controller.mutationError instanceof Error
                ? controller.mutationError.message
                : '工作流操作失败'
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

function ComfyuiPanel() {
  const controller = useOptionalComfyui()
  if (!controller) {
    throw new Error('ComfyuiRuntime is required')
  }
  return <ComfyuiWorkflowsPanel {...controller.comfyuiPanelProps} />
}

function ComfyuiRunModalHost() {
  const controller = useOptionalComfyui()
  const runModal = controller?.comfyuiRunModal
  const workflow = runModal?.workflow
  if (!workflow || !runModal) {
    return null
  }
  return (
    <ComfyuiRunModal
      key={String(workflow.id)}
      workflow={workflow}
      onClose={runModal.onClose}
    />
  )
}
