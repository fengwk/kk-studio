import { useEffect, useState } from 'react'
import { FilePlus } from 'lucide-react'
import { Button } from '@/shared/ui/controls/Button'
import { Dialog } from '@/shared/ui/overlays/Dialog'
import type { ProjectsApi } from '../projects-api'
import { projectsApi } from '../projects-api'
import type { IssueDTO } from '../types'

export interface CreateIssueModalProps {
  isOpen: boolean
  projectId: string
  onClose: () => void
  onSuccess: (created: IssueDTO) => void
  api?: ProjectsApi
}

export function CreateIssueModal({
  isOpen,
  projectId,
  onClose,
  onSuccess,
  api = projectsApi,
}: CreateIssueModalProps) {
  const [title, setTitle] = useState('')
  const [description, setDescription] = useState('')
  const [isSubmitting, setIsSubmitting] = useState(false)
  const [errorMessage, setErrorMessage] = useState<string | null>(null)

  useEffect(() => {
    if (isOpen) {
      setTitle('')
      setDescription('')
      setErrorMessage(null)
    }
  }, [isOpen])

  if (!isOpen) {
    return null
  }

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault()
    const trimmedTitle = title.trim()
    if (!trimmedTitle) {
      setErrorMessage('Issue 标题不能为空')
      return
    }

    setIsSubmitting(true)
    setErrorMessage(null)
    try {
      const created = await api.createIssue(projectId, {
        title: trimmedTitle,
        description: description.trim() || null,
      })
      onSuccess(created)
      onClose()
    } catch (err) {
      setErrorMessage(err instanceof Error ? err.message : '创建 Issue 失败')
    } finally {
      setIsSubmitting(false)
    }
  }

  return (
    <Dialog
      className="resource-modal-card"
      title="新建 Issue"
      headerIcon={<FilePlus size={18} aria-hidden="true" />}
      pending={isSubmitting}
      onClose={onClose}
    >
        <form className="modal-card-form" onSubmit={handleSubmit}>
          <div className="modal-body">
            {errorMessage && (
              <div className="form-error-banner" role="alert">
                <span>{errorMessage}</span>
              </div>
            )}

            <div className="form-group">
              <label htmlFor="create-issue-title" className="form-label required">
                需求标题
              </label>
              <input
                id="create-issue-title"
                type="text"
                className="form-input"
                value={title}
                onChange={(e) => setTitle(e.target.value)}
                placeholder="例如：支持批量导入视频元数据"
                disabled={isSubmitting}
                autoFocus
              />
            </div>

            <div className="form-group">
              <label htmlFor="create-issue-desc" className="form-label">
                需求事实与描述
              </label>
              <textarea
                id="create-issue-desc"
                className="form-textarea"
                rows={4}
                value={description}
                onChange={(e) => setDescription(e.target.value)}
                placeholder="描述需求背景、验收标准及阶段交付物要求..."
                disabled={isSubmitting}
              />
            </div>

            <div style={{ fontSize: '12px', color: 'var(--fg-dim)' }}>
              <span>创建后将自动初始化进入 <code>INIT</code> 阶段。</span>
            </div>
          </div>

          <div className="modal-footer">
            <Button variant="ghost" onClick={onClose} disabled={isSubmitting}>
              取消
            </Button>
            <Button type="submit" disabled={isSubmitting}>
              {isSubmitting ? '创建中...' : '创建 Issue'}
            </Button>
          </div>
        </form>
    </Dialog>
  )
}
