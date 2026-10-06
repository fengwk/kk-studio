import { useEffect, useState } from 'react'
import { FolderPlus } from 'lucide-react'
import { Button } from '@/shared/ui/controls/Button'
import { Checkbox } from '@/shared/ui/controls/Checkbox'
import { Dialog } from '@/shared/ui/overlays/Dialog'
import type { ProjectsApi } from '../projects-api'
import { projectsApi } from '../projects-api'
import type { ProjectDTO } from '../types'

export interface CreateProjectModalProps {
  isOpen: boolean
  onClose: () => void
  onSuccess: (created: ProjectDTO) => void
  api?: ProjectsApi
}

export function CreateProjectModal({
  isOpen,
  onClose,
  onSuccess,
  api = projectsApi,
}: CreateProjectModalProps) {
  const [title, setTitle] = useState('')
  const [description, setDescription] = useState('')
  const [yoloEnabled, setYoloEnabled] = useState(true)
  const [isSubmitting, setIsSubmitting] = useState(false)
  const [errorMessage, setErrorMessage] = useState<string | null>(null)

  useEffect(() => {
    if (isOpen) {
      setTitle('')
      setDescription('')
      setYoloEnabled(true)
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
      setErrorMessage('项目名称不能为空')
      return
    }

    setIsSubmitting(true)
    setErrorMessage(null)
    try {
      const created = await api.createProject({
        title: trimmedTitle,
        description: description.trim() || null,
        yoloEnabled,
      })
      onSuccess(created)
      onClose()
    } catch (err) {
      setErrorMessage(err instanceof Error ? err.message : '创建项目失败')
    } finally {
      setIsSubmitting(false)
    }
  }

  return (
    <Dialog
      className="resource-modal-card"
      title="新建项目"
      headerIcon={<FolderPlus size={18} aria-hidden="true" />}
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
              <label htmlFor="create-project-title" className="form-label required">
                项目名称
              </label>
              <input
                id="create-project-title"
                type="text"
                className="form-input"
                value={title}
                onChange={(e) => setTitle(e.target.value)}
                placeholder="例如：视频生成流水线"
                disabled={isSubmitting}
                autoFocus
              />
            </div>

            <div className="form-group">
              <label htmlFor="create-project-desc" className="form-label">
                项目描述
              </label>
              <textarea
                id="create-project-desc"
                className="form-textarea"
                rows={3}
                value={description}
                onChange={(e) => setDescription(e.target.value)}
                placeholder="描述项目的目标与业务范围..."
                disabled={isSubmitting}
              />
            </div>

            <div className="form-group">
              <Checkbox
                checked={yoloEnabled}
                onChange={setYoloEnabled}
                label="启用 YOLO 执行策略 (自主执行，跳过人工交互门禁)"
                disabled={isSubmitting}
              />
            </div>
          </div>

          <div className="modal-footer">
            <Button variant="ghost" onClick={onClose} disabled={isSubmitting}>
              取消
            </Button>
            <Button type="submit" disabled={isSubmitting}>
              {isSubmitting ? '创建中...' : '创建项目'}
            </Button>
          </div>
        </form>
    </Dialog>
  )
}
