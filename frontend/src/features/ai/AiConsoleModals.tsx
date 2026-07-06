import { Trash2 } from 'lucide-react'
import type { FormEventHandler, ReactNode } from 'react'
import { AgentForm, ModelForm, ProviderForm } from '@/features/ai/AiResourceForms'
import type { AgentDraft, ConfirmModalState, ModelDraft, ProviderDraft, ResourceModal } from '@/features/ai/ai-console-types'
import { resourceTitle } from '@/features/ai/ai-console-utils'
import type { AgentDefinitionDTO, AgentModelDTO, AgentProviderDTO } from '@/shared/api/contracts'

export function CreateSessionModal({
  open,
  agents,
  selectedAgentName,
  sessionTitle,
  pending,
  onClose,
  onSelectAgent,
  onSessionTitleChange,
  onSubmit,
}: {
  open: boolean
  agents: AgentDefinitionDTO[]
  selectedAgentName: string
  sessionTitle: string
  pending: boolean
  onClose: () => void
  onSelectAgent: (agentName: string) => void
  onSessionTitleChange: (title: string) => void
  onSubmit: FormEventHandler<HTMLFormElement>
}) {
  if (!open) {
    return null
  }

  return (
    <ModalBackdrop onClose={onClose}>
      <form className="modal-card" aria-label="新建 Chat" onSubmit={onSubmit} onMouseDown={(event) => event.stopPropagation()}>
        <ModalHeader title="新建 Chat" onClose={onClose} />
        <div className="modal-body">
          <label className="form-group">
            <span>Agent</span>
            <select value={selectedAgentName} onChange={(event) => onSelectAgent(event.target.value)} required>
              {agents.map((agent) => (
                <option key={agent.id} value={agent.name}>
                  {agent.name}
                </option>
              ))}
            </select>
          </label>
          <label className="form-group">
            <span>Title</span>
            <input value={sessionTitle} onChange={(event) => onSessionTitleChange(event.target.value)} placeholder="会话标题" />
          </label>
        </div>
        <div className="modal-footer">
          <button type="submit" className="btn-primary" disabled={!selectedAgentName || pending}>
            确认创建
          </button>
        </div>
      </form>
    </ModalBackdrop>
  )
}

export function EditSessionModal({
  open,
  title,
  pending,
  onClose,
  onTitleChange,
  onSubmit,
}: {
  open: boolean
  title: string
  pending: boolean
  onClose: () => void
  onTitleChange: (title: string) => void
  onSubmit: FormEventHandler<HTMLFormElement>
}) {
  if (!open) {
    return null
  }

  return (
    <ModalBackdrop onClose={onClose}>
      <form className="modal-card" aria-label="编辑 Chat" onSubmit={onSubmit} onMouseDown={(event) => event.stopPropagation()}>
        <ModalHeader title="编辑 Chat" onClose={onClose} />
        <div className="modal-body">
          <label className="form-group">
            <span>Title</span>
            <input value={title} onChange={(event) => onTitleChange(event.target.value)} placeholder="会话标题" />
          </label>
        </div>
        <div className="modal-footer">
          <button type="submit" className="btn-primary" disabled={pending}>
            保存修改
          </button>
        </div>
      </form>
    </ModalBackdrop>
  )
}

export function ResourceEditorModal({
  modal,
  providers,
  models,
  providerDraft,
  modelDraft,
  agentDraft,
  pending,
  onClose,
  onProviderDraftChange,
  onModelDraftChange,
  onAgentDraftChange,
  onSubmit,
}: {
  modal: ResourceModal | null
  providers: AgentProviderDTO[]
  models: AgentModelDTO[]
  providerDraft: ProviderDraft
  modelDraft: ModelDraft
  agentDraft: AgentDraft
  pending: boolean
  onClose: () => void
  onProviderDraftChange: (draft: ProviderDraft) => void
  onModelDraftChange: (draft: ModelDraft) => void
  onAgentDraftChange: (draft: AgentDraft) => void
  onSubmit: FormEventHandler<HTMLFormElement>
}) {
  if (!modal) {
    return null
  }

  const title = resourceTitle(modal)
  return (
    <ModalBackdrop onClose={onClose}>
      <form className="modal-card resource-modal-card" aria-label={title} onSubmit={onSubmit} onMouseDown={(event) => event.stopPropagation()}>
        <ModalHeader title={title} onClose={onClose} />
        <div className="modal-body">
          {modal.kind === 'provider' && <ProviderForm draft={providerDraft} onChange={onProviderDraftChange} />}
          {modal.kind === 'model' && <ModelForm draft={modelDraft} mode={modal.mode} providers={providers} onChange={onModelDraftChange} />}
          {modal.kind === 'agent' && <AgentForm draft={agentDraft} models={models} onChange={onAgentDraftChange} />}
        </div>
        <div className="modal-footer">
          <button type="submit" className="btn-primary" disabled={pending}>
            {modal.mode === 'create' ? '确认创建' : '保存修改'}
          </button>
        </div>
      </form>
    </ModalBackdrop>
  )
}

export function ConfirmActionModal({
  modal,
  pending,
  onClose,
}: {
  modal: ConfirmModalState | null
  pending: boolean
  onClose: () => void
}) {
  if (!modal) {
    return null
  }

  return (
    <ModalBackdrop onClose={pending ? () => undefined : onClose}>
      <div className="modal-card confirm-modal-card" role="alertdialog" aria-label={modal.title} onMouseDown={(event) => event.stopPropagation()}>
        <ModalHeader title={modal.title} onClose={pending ? () => undefined : onClose} />
        <div className="modal-body confirm-modal-body">
          <div className={`confirm-modal-icon ${modal.tone === 'danger' ? 'danger' : ''}`} aria-hidden="true">
            <Trash2 />
          </div>
          <p className="confirm-modal-description">{modal.description}</p>
        </div>
        <div className="modal-footer">
          <button type="button" className="ghost-btn" onClick={onClose} disabled={pending}>
            取消
          </button>
          <button type="button" className={`btn-primary ${modal.tone === 'danger' ? 'danger' : ''}`} onClick={modal.onConfirm} disabled={pending}>
            {modal.confirmLabel || '确认'}
          </button>
        </div>
      </div>
    </ModalBackdrop>
  )
}

function ModalBackdrop({ onClose, children }: { onClose: () => void; children: ReactNode }) {
  return (
    <div className="modal-backdrop" role="presentation" onMouseDown={onClose}>
      {children}
    </div>
  )
}

function ModalHeader({ title, onClose }: { title: string; onClose: () => void }) {
  return (
    <div className="modal-header">
      <h2>{title}</h2>
      <button type="button" className="ghost-btn" onClick={onClose}>
        关闭
      </button>
    </div>
  )
}
