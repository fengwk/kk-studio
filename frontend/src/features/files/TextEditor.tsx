import { useCallback, useEffect, useRef, useState } from 'react'
import { AlertTriangle, Check, Copy, RefreshCw, Save } from 'lucide-react'
import { isConflictError } from '@/shared/api/client'
import { presentConflict } from '@/shared/conflict/conflict-presenter'
import type { CloudFilesApi } from './cloud-files-api'
import { cloudFilesApi } from './cloud-files-api'
import { formatBytes } from './files-utils'
import type { CloudFileSnapshotDTO } from './types'

interface TextEditorProps {
  snapshot: CloudFileSnapshotDTO
  api?: CloudFilesApi
  onSaveSuccess?: (path: string) => void
  onReload?: () => void
}

function assembleTextContent(snapshot: CloudFileSnapshotDTO): string {
  if (!snapshot.text) {
    return ''
  }
  const body = snapshot.text.lines.map((l) => l.content).join('\n')
  return snapshot.text.endsWithNewline ? `${body}\n` : body
}

export function TextEditor({
  snapshot,
  api = cloudFilesApi,
  onSaveSuccess,
  onReload,
}: TextEditorProps) {
  const initialContent = assembleTextContent(snapshot)
  const initialRevision = snapshot.text?.revision ?? snapshot.node.version

  const [content, setContent] = useState(initialContent)
  const [savedContent, setSavedContent] = useState(initialContent)
  const [expectedRevision, setExpectedRevision] = useState(initialRevision)
  const [isSaving, setIsSaving] = useState(false)
  const [conflict, setConflict] = useState<{ reason: string; detail: string } | null>(null)
  const [errorMessage, setErrorMessage] = useState<string | null>(null)
  const [statusMessage, setStatusMessage] = useState<string | null>(null)
  const [copiedPath, setCopiedPath] = useState(false)

  const textareaRef = useRef<HTMLTextAreaElement>(null)
  const lastLoadedPathRef = useRef(snapshot.node.path)
  const savedContentRef = useRef(initialContent)

  // Reset or update content when a new node is selected or current node refreshed
  useEffect(() => {
    const isNewNode = lastLoadedPathRef.current !== snapshot.node.path
    lastLoadedPathRef.current = snapshot.node.path

    const newContent = assembleTextContent(snapshot)
    const newRev = snapshot.text?.revision ?? snapshot.node.version

    if (isNewNode) {
      savedContentRef.current = newContent
      setContent(newContent)
      setSavedContent(newContent)
      setExpectedRevision(newRev)
      setConflict(null)
      setErrorMessage(null)
      setStatusMessage(null)
    } else {
      // Same path refreshed: only update revision and savedContent;
      // If user hasn't modified content, sync content. If user has unsaved draft, retain it!
      const oldSaved = savedContentRef.current
      savedContentRef.current = newContent
      setExpectedRevision(newRev)
      setSavedContent(newContent)
      setContent((prev) => (prev === oldSaved ? newContent : prev))
    }
  }, [snapshot])

  const isDirty = content !== savedContent

  const handleSave = useCallback(async () => {
    if (isSaving) {
      return
    }
    setIsSaving(true)
    setErrorMessage(null)
    setStatusMessage(null)

    try {
      await api.saveText({
        path: snapshot.node.path,
        content,
        expectedRevision,
      })
      savedContentRef.current = content
      setSavedContent(content)
      setConflict(null)
      setStatusMessage('保存成功')
      onSaveSuccess?.(snapshot.node.path)
      setTimeout(() => setStatusMessage(null), 2500)
    } catch (err) {
      if (isConflictError(err)) {
        const presentation = presentConflict(err)
        setConflict({
          reason: presentation?.reason || 'CLOUD_REVISION_CONFLICT',
          detail: presentation?.detail || '服务端内容已被修改，请选择重新加载或使用最新版本重试保存。',
        })
      } else {
        setErrorMessage(err instanceof Error ? err.message : '保存失败')
      }
    } finally {
      setIsSaving(false)
    }
  }, [api, content, expectedRevision, isSaving, onSaveSuccess, snapshot.node.path])

  const handleDiscardAndReload = async () => {
    setConflict(null)
    setErrorMessage(null)
    if (onReload) {
      onReload()
    } else {
      try {
        const fresh = await api.getFileSnapshot(snapshot.node.path)
        const freshContent = assembleTextContent(fresh)
        savedContentRef.current = freshContent
        setContent(freshContent)
        setSavedContent(freshContent)
        setExpectedRevision(fresh.text?.revision ?? fresh.node.version)
      } catch (err) {
        setErrorMessage(err instanceof Error ? err.message : '重新加载失败')
      }
    }
  }

  const handleRetryOverwrite = async () => {
    setIsSaving(true)
    setErrorMessage(null)
    try {
      // 1. Fetch latest server revision
      const fresh = await api.getFileSnapshot(snapshot.node.path)
      const latestRev = fresh.text?.revision ?? fresh.node.version
      setExpectedRevision(latestRev)

      // 2. Resubmit with the latest revision and current draft
      await api.saveText({
        path: snapshot.node.path,
        content,
        expectedRevision: latestRev,
      })
      savedContentRef.current = content
      setSavedContent(content)
      setConflict(null)
      setStatusMessage('已强制保存最新版本')
      onSaveSuccess?.(snapshot.node.path)
      setTimeout(() => setStatusMessage(null), 2500)
    } catch (err) {
      if (isConflictError(err)) {
        const presentation = presentConflict(err)
        setConflict({
          reason: presentation?.reason || 'CLOUD_REVISION_CONFLICT',
          detail: presentation?.detail || '重试时版本再次冲突。',
        })
      } else {
        setErrorMessage(err instanceof Error ? err.message : '重试保存失败')
      }
    } finally {
      setIsSaving(false)
    }
  }

  const handleCopyPath = async () => {
    try {
      await navigator.clipboard.writeText(snapshot.node.path)
      setCopiedPath(true)
      setTimeout(() => setCopiedPath(false), 2000)
    } catch {
      // Ignore clipboard write failures
    }
  }

  // Keyboard shortcut: Ctrl+S / Cmd+S
  const handleKeyDown = (e: React.KeyboardEvent<HTMLTextAreaElement>) => {
    if ((e.ctrlKey || e.metaKey) && e.key === 's') {
      e.preventDefault()
      if (isDirty && !isSaving) {
        void handleSave()
      }
    }
  }

  const lineCount = content.split('\n').length
  const charCount = content.length

  return (
    <div className="text-editor-container" data-testid="text-editor">
      <div className="text-editor-toolbar">
        <div className="text-editor-path-info">
          <span className="text-editor-filename" title={snapshot.node.name}>
            {snapshot.node.name}
          </span>
          <span className="text-editor-path" title={snapshot.node.path}>
            {snapshot.node.path}
          </span>
          <button
            type="button"
            className="btn-icon"
            onClick={handleCopyPath}
            aria-label={copiedPath ? '已复制路径' : '复制路径'}
            title="复制文件路径"
          >
            {copiedPath ? <Check size={14} /> : <Copy size={14} />}
          </button>
          {isDirty && (
            <span className="badge-dirty" role="status" aria-label="未保存的更改">
              未保存
            </span>
          )}
        </div>

        <div className="text-editor-actions">
          <span className="badge-revision" title={`Revision: ${expectedRevision}`}>
            Rev: {expectedRevision}
          </span>
          <button
            type="button"
            className="btn-save"
            onClick={handleSave}
            disabled={!isDirty || isSaving}
            aria-label="保存文件"
          >
            <Save size={16} aria-hidden="true" />
            <span>{isSaving ? '保存中...' : '保存'}</span>
          </button>
        </div>
      </div>

      {conflict && (
        <div
          className="text-editor-conflict-banner"
          role="alertdialog"
          aria-label="持久状态已变化"
          data-testid="conflict-banner"
        >
          <div className="conflict-banner-content">
            <AlertTriangle className="conflict-icon" size={18} aria-hidden="true" />
            <div className="conflict-text">
              <strong>版本冲突：{conflict.reason}</strong>
              <p>{conflict.detail}</p>
            </div>
          </div>
          <div className="conflict-banner-buttons">
            <button
              type="button"
              className="btn-ghost"
              onClick={handleDiscardAndReload}
              disabled={isSaving}
            >
              <RefreshCw size={14} aria-hidden="true" />
              <span>重新加载（放弃草稿）</span>
            </button>
            <button
              type="button"
              className="btn-primary"
              onClick={handleRetryOverwrite}
              disabled={isSaving}
            >
              <span>使用当前草稿重试保存</span>
            </button>
          </div>
        </div>
      )}

      {errorMessage && (
        <div className="text-editor-error-banner" role="alert">
          <AlertTriangle size={16} aria-hidden="true" />
          <span>{errorMessage}</span>
        </div>
      )}

      {statusMessage && (
        <div className="text-editor-status-banner" role="status">
          <Check size={16} aria-hidden="true" />
          <span>{statusMessage}</span>
        </div>
      )}

      <div className="text-editor-textarea-wrapper">
        <label htmlFor="cloud-file-text-content" className="sr-only">
          文件正文内容
        </label>
        <textarea
          id="cloud-file-text-content"
          ref={textareaRef}
          className="text-editor-textarea"
          value={content}
          onChange={(e) => setContent(e.target.value)}
          onKeyDown={handleKeyDown}
          spellCheck={false}
          aria-label={`编辑 ${snapshot.node.name}`}
          data-testid="text-editor-textarea"
        />
      </div>

      <div className="text-editor-footer">
        <span>{lineCount} 行</span>
        <span>{charCount} 字符</span>
        <span>{formatBytes(snapshot.node.sizeBytes ?? charCount)}</span>
      </div>
    </div>
  )
}
