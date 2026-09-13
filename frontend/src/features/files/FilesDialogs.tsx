import { useEffect, useRef, useState } from 'react'
import { FolderPlus, FilePlus, ArrowRightLeft, Trash2, X, AlertTriangle } from 'lucide-react'
import type { CloudNodeDTO } from './types'

interface BaseModalProps {
  isOpen: boolean
  onClose: () => void
  title: string
  children: React.ReactNode
}

function BaseModal({ isOpen, onClose, title, children }: BaseModalProps) {
  useEffect(() => {
    if (!isOpen) {
      return
    }
    const handleKeyDown = (e: KeyboardEvent) => {
      if (e.key === 'Escape') {
        e.preventDefault()
        onClose()
      }
    }
    window.addEventListener('keydown', handleKeyDown)
    return () => window.removeEventListener('keydown', handleKeyDown)
  }, [isOpen, onClose])

  if (!isOpen) {
    return null
  }

  return (
    <div
      className="modal-backdrop"
      role="presentation"
      onClick={(e) => {
        if (e.target === e.currentTarget) {
          onClose()
        }
      }}
    >
      <div
        className="modal-card files-modal-card"
        role="dialog"
        aria-modal="true"
        aria-label={title}
      >
        <div className="modal-header">
          <h3>{title}</h3>
          <button
            type="button"
            className="modal-close-button"
            onClick={onClose}
            aria-label="关闭"
          >
            <X size={16} aria-hidden="true" />
          </button>
        </div>
        {children}
      </div>
    </div>
  )
}

// 1. Create Directory Modal
export function CreateDirectoryModal({
  isOpen,
  currentDirectory,
  onClose,
  onSubmit,
}: {
  isOpen: boolean
  currentDirectory: string
  onClose: () => void
  onSubmit: (path: string) => Promise<void>
}) {
  const [name, setName] = useState('')
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const inputRef = useRef<HTMLInputElement>(null)

  useEffect(() => {
    if (isOpen) {
      setName('')
      setError(null)
      setLoading(false)
      setTimeout(() => inputRef.current?.focus(), 50)
    }
  }, [isOpen])

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault()
    const trimmed = name.trim()
    if (!trimmed) {
      setError('目录名不能为空')
      return
    }
    if (trimmed.includes('/')) {
      setError('目录名不能包含斜杠')
      return
    }
    const targetPath =
      currentDirectory === '/' ? `/${trimmed}` : `${currentDirectory}/${trimmed}`

    setLoading(true)
    setError(null)
    try {
      await onSubmit(targetPath)
      onClose()
    } catch (err) {
      setError(err instanceof Error ? err.message : '创建目录失败')
    } finally {
      setLoading(false)
    }
  }

  return (
    <BaseModal isOpen={isOpen} onClose={onClose} title="新建目录">
      <form onSubmit={handleSubmit} className="modal-body">
        <div className="files-dialog-field">
          <label htmlFor="create-dir-parent">目标位置</label>
          <input
            id="create-dir-parent"
            type="text"
            readOnly
            value={currentDirectory}
            className="files-input-readonly"
          />
        </div>
        <div className="files-dialog-field">
          <label htmlFor="create-dir-name">目录名称</label>
          <input
            id="create-dir-name"
            ref={inputRef}
            type="text"
            value={name}
            onChange={(e) => setName(e.target.value)}
            placeholder="例如: documents"
            disabled={loading}
          />
        </div>
        {error && (
          <div className="files-dialog-error" role="alert">
            <AlertTriangle size={14} aria-hidden="true" />
            <span>{error}</span>
          </div>
        )}
        <div className="modal-actions">
          <button type="button" className="btn-ghost" onClick={onClose} disabled={loading}>
            取消
          </button>
          <button type="submit" className="btn-primary" disabled={loading}>
            <FolderPlus size={16} aria-hidden="true" />
            <span>{loading ? '创建中...' : '创建'}</span>
          </button>
        </div>
      </form>
    </BaseModal>
  )
}

// 2. Create Text File Modal
export function CreateFileModal({
  isOpen,
  currentDirectory,
  onClose,
  onSubmit,
}: {
  isOpen: boolean
  currentDirectory: string
  onClose: () => void
  onSubmit: (path: string) => Promise<void>
}) {
  const [name, setName] = useState('')
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const inputRef = useRef<HTMLInputElement>(null)

  useEffect(() => {
    if (isOpen) {
      setName('')
      setError(null)
      setLoading(false)
      setTimeout(() => inputRef.current?.focus(), 50)
    }
  }, [isOpen])

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault()
    const trimmed = name.trim()
    if (!trimmed) {
      setError('文件名不能为空')
      return
    }
    if (trimmed.includes('/')) {
      setError('文件名不能包含斜杠')
      return
    }
    const targetPath =
      currentDirectory === '/' ? `/${trimmed}` : `${currentDirectory}/${trimmed}`

    setLoading(true)
    setError(null)
    try {
      await onSubmit(targetPath)
      onClose()
    } catch (err) {
      setError(err instanceof Error ? err.message : '创建文件失败')
    } finally {
      setLoading(false)
    }
  }

  return (
    <BaseModal isOpen={isOpen} onClose={onClose} title="新建文本文件">
      <form onSubmit={handleSubmit} className="modal-body">
        <div className="files-dialog-field">
          <label htmlFor="create-file-parent">目标位置</label>
          <input
            id="create-file-parent"
            type="text"
            readOnly
            value={currentDirectory}
            className="files-input-readonly"
          />
        </div>
        <div className="files-dialog-field">
          <label htmlFor="create-file-name">文件名称</label>
          <input
            id="create-file-name"
            ref={inputRef}
            type="text"
            value={name}
            onChange={(e) => setName(e.target.value)}
            placeholder="例如: notes.md 或 script.py"
            disabled={loading}
          />
        </div>
        {error && (
          <div className="files-dialog-error" role="alert">
            <AlertTriangle size={14} aria-hidden="true" />
            <span>{error}</span>
          </div>
        )}
        <div className="modal-actions">
          <button type="button" className="btn-ghost" onClick={onClose} disabled={loading}>
            取消
          </button>
          <button type="submit" className="btn-primary" disabled={loading}>
            <FilePlus size={16} aria-hidden="true" />
            <span>{loading ? '创建中...' : '创建'}</span>
          </button>
        </div>
      </form>
    </BaseModal>
  )
}

// 3. Move / Rename Modal
export function MoveNodeModal({
  isOpen,
  node,
  onClose,
  onSubmit,
}: {
  isOpen: boolean
  node: CloudNodeDTO | null
  onClose: () => void
  onSubmit: (sourcePath: string, destinationPath: string, expectedVersion: string) => Promise<void>
}) {
  const [destinationPath, setDestinationPath] = useState('')
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const inputRef = useRef<HTMLInputElement>(null)

  useEffect(() => {
    if (isOpen && node) {
      setDestinationPath(node.path)
      setError(null)
      setLoading(false)
      setTimeout(() => inputRef.current?.focus(), 50)
    }
  }, [isOpen, node])

  if (!node) {
    return null
  }

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault()
    const trimmed = destinationPath.trim()
    if (!trimmed) {
      setError('目标路径不能为空')
      return
    }
    if (!trimmed.startsWith('/')) {
      setError('路径必须以 / 开头')
      return
    }
    if (trimmed === node.path) {
      onClose()
      return
    }

    setLoading(true)
    setError(null)
    try {
      await onSubmit(node.path, trimmed, node.version)
      onClose()
    } catch (err) {
      setError(err instanceof Error ? err.message : '移动失败')
    } finally {
      setLoading(false)
    }
  }

  return (
    <BaseModal isOpen={isOpen} onClose={onClose} title={`重命名 / 移动: ${node.name}`}>
      <form onSubmit={handleSubmit} className="modal-body">
        <div className="files-dialog-field">
          <label htmlFor="move-source-path">当前路径</label>
          <input
            id="move-source-path"
            type="text"
            readOnly
            value={node.path}
            className="files-input-readonly"
          />
        </div>
        <div className="files-dialog-field">
          <label htmlFor="move-dest-path">目标新路径</label>
          <input
            id="move-dest-path"
            ref={inputRef}
            type="text"
            value={destinationPath}
            onChange={(e) => setDestinationPath(e.target.value)}
            disabled={loading}
          />
        </div>
        {error && (
          <div className="files-dialog-error" role="alert">
            <AlertTriangle size={14} aria-hidden="true" />
            <span>{error}</span>
          </div>
        )}
        <div className="modal-actions">
          <button type="button" className="btn-ghost" onClick={onClose} disabled={loading}>
            取消
          </button>
          <button type="submit" className="btn-primary" disabled={loading}>
            <ArrowRightLeft size={16} aria-hidden="true" />
            <span>{loading ? '移动中...' : '确定'}</span>
          </button>
        </div>
      </form>
    </BaseModal>
  )
}

// 4. Delete Confirmation Modal
export function DeleteConfirmModal({
  isOpen,
  node,
  onClose,
  onSubmit,
}: {
  isOpen: boolean
  node: CloudNodeDTO | null
  onClose: () => void
  onSubmit: (path: string, expectedVersion: string) => Promise<void>
}) {
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    if (isOpen) {
      setError(null)
      setLoading(false)
    }
  }, [isOpen])

  if (!node) {
    return null
  }

  const isDirectory = node.kind === 'DIRECTORY'

  const handleConfirm = async () => {
    setLoading(true)
    setError(null)
    try {
      await onSubmit(node.path, node.version)
      onClose()
    } catch (err) {
      setError(err instanceof Error ? err.message : '删除失败')
    } finally {
      setLoading(false)
    }
  }

  return (
    <div
      className="modal-backdrop"
      role="presentation"
      onClick={(e) => {
        if (e.target === e.currentTarget && !loading) {
          onClose()
        }
      }}
    >
      <div
        className="modal-card files-modal-card confirm-modal-card"
        role="alertdialog"
        aria-modal="true"
        aria-label={`确认删除 ${node.name}`}
      >
        <div className="modal-header">
          <h3>确认删除</h3>
          <button
            type="button"
            className="modal-close-button"
            onClick={onClose}
            disabled={loading}
            aria-label="关闭"
          >
            <X size={16} aria-hidden="true" />
          </button>
        </div>
        <div className="modal-body">
          <div className="confirm-modal-icon danger" aria-hidden="true">
            <Trash2 size={24} />
          </div>
          <p className="confirm-modal-description">
            您确定要删除 {isDirectory ? '空目录' : '文件'}{' '}
            <strong>{node.name}</strong> 吗？
            {isDirectory && ' （注意：非空目录无法被删除）'}
          </p>
          <div className="files-dialog-field">
            <span className="files-field-hint">完整路径: {node.path}</span>
          </div>
          {error && (
            <div className="files-dialog-error" role="alert">
              <AlertTriangle size={14} aria-hidden="true" />
              <span>{error}</span>
            </div>
          )}
          <div className="modal-actions">
            <button type="button" className="btn-ghost" onClick={onClose} disabled={loading}>
              取消
            </button>
            <button
              type="button"
              className="btn-danger"
              onClick={handleConfirm}
              disabled={loading}
            >
              <span>{loading ? '正在删除...' : '确认删除'}</span>
            </button>
          </div>
        </div>
      </div>
    </div>
  )
}
