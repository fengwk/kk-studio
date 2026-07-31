import { type ReactNode } from 'react'
import { Link } from 'react-router'
import { SearchField, StateBlock } from '@/shared/ui/console/AiConsoleCommonCards'
import { useComfyui } from '@/features/comfyui/ComfyuiContext'
import { ComfyuiRunModal } from '@/features/comfyui/ComfyuiRunModal'
import { ComfyuiRuntime } from '@/features/comfyui/ComfyuiRuntime'
import { ComfyuiWorkflowsPanel } from '@/features/comfyui/ComfyuiWorkflowsPanel'
import type { ExtensionComponentProps } from '@/platform/extensions/types'
import { useI18n } from '@/shared/i18n'

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
  const controller = useComfyui()
  const { t } = useI18n()
  return (
    <section className="screen active">
      <nav className="subbar">
        <nav className="subnav" aria-label={t('comfyui.navigation.tools')}>
          <Link className="active" to="/comfyui">
            ComfyUI
          </Link>
        </nav>
        <SearchField value={controller.search} onChange={controller.setSearch} />
      </nav>
      <div className="screen-body">
        {controller.busy && <StateBlock title={t('comfyui.page.loading')} />}
        {controller.error && (
          <StateBlock
            title={
              controller.error instanceof Error
                ? controller.error.message
                : t('comfyui.page.workflowLoadFailed')
            }
            tone="danger"
          />
        )}
        {controller.mutationError && (
          <StateBlock
            title={
              controller.mutationError instanceof Error
                ? controller.mutationError.message
                : t('comfyui.page.workflowOperationFailed')
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
  const controller = useComfyui()
  return <ComfyuiWorkflowsPanel {...controller.comfyuiPanelProps} />
}

function ComfyuiRunModalHost() {
  const controller = useComfyui()
  const runModal = controller.comfyuiRunModal
  if (!runModal.workflow) {
    return null
  }
  return (
    <ComfyuiRunModal
      key={String(runModal.workflow.id)}
      workflow={runModal.workflow}
      onClose={runModal.onClose}
    />
  )
}
