import {
  createContext,
  useContext,
  type PropsWithChildren,
  type ReactNode,
} from 'react'
import { Link } from 'react-router-dom'
import { ConfirmActionModal } from '@/features/ai/shared/ConfirmActionModal'
import { SearchField, StateBlock } from '@/features/ai/shared/AiConsoleCommonCards'
import {
  ComfyuiRunModal,
  ComfyuiWorkflowEditorModal,
  ComfyuiWorkflowsPanel,
  useComfyuiPageController,
} from '@/features/comfyui'
import type { ExtensionComponentProps } from '@/platform/extensions/types'

type ComfyuiPageController = ReturnType<typeof useComfyuiPageController>

const ComfyuiContext = createContext<ComfyuiPageController | null>(null)

export function ComfyuiPage({ children }: ExtensionComponentProps) {
  return (
    <ComfyuiRuntime>
      <ComfyuiFrame content={<ComfyuiPanel />}>{children}</ComfyuiFrame>
      <ComfyuiRunModalHost />
    </ComfyuiRuntime>
  )
}

function ComfyuiRuntime({ children }: PropsWithChildren) {
  const controller = useComfyuiPageController()
  return (
    <ComfyuiContext.Provider value={controller}>{children}</ComfyuiContext.Provider>
  )
}

function useOptionalComfyui() {
  return useContext(ComfyuiContext)
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

export function ComfyuiWorkflowEditorDialog() {
  const controller = useOptionalComfyui()
  return controller ? (
    <ComfyuiWorkflowEditorModal {...controller.comfyuiEditorModal} />
  ) : null
}

export function ComfyuiDeleteDialog() {
  const controller = useOptionalComfyui()
  return controller ? (
    <ConfirmActionModal {...controller.comfyuiDeleteConfirmModal} />
  ) : null
}
