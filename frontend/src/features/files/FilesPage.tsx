import { useCallback, useEffect, useImperativeHandle, useRef, useState } from 'react'
import {
  FilePlus,
  Folder,
  FolderPlus,
  Menu,
  RefreshCw,
  Upload,
  AlertTriangle,
} from 'lucide-react'
import type { CloudFilesApi } from './cloud-files-api'
import { cloudFilesApi } from './cloud-files-api'
import { BlobPreview } from './BlobPreview'
import {
  CreateDirectoryModal,
  CreateFileModal,
  DeleteConfirmModal,
  MoveNodeModal,
} from './FilesDialogs'
import { FilesTree } from './FilesTree'
import { TextEditor } from './TextEditor'
import { useCloudFilesInvalidation } from './useCloudFilesInvalidation'
import { getParentPath, normalizePath } from './files-utils'
import type { CloudFileSnapshotDTO, CloudNodeDTO } from './types'
import './files.css'

export interface FilesPageHandle {
  openPath: (path: string) => Promise<void>
}

export interface FilesPageProps {
  initialPath?: string
  api?: CloudFilesApi
  ref?: React.Ref<FilesPageHandle>
}

export function FilesPage({
  initialPath,
  api = cloudFilesApi,
  ref,
}: FilesPageProps) {
  // Tree state
  const [rootSnapshot, setRootSnapshot] = useState<CloudFileSnapshotDTO | null>(null)
  const [directoryChildrenMap, setDirectoryChildrenMap] = useState<Record<string, CloudNodeDTO[]>>({})
  const [expandedPaths, setExpandedPaths] = useState<Set<string>>(new Set(['/']))
  const [loadingPaths, setLoadingPaths] = useState<Set<string>>(new Set())
  const [treeLoading, setTreeLoading] = useState(false)
  const [treeError, setTreeError] = useState<string | null>(null)

  // Selection state
  const [selectedPath, setSelectedPath] = useState<string | null>(null)
  const [selectedSnapshot, setSelectedSnapshot] = useState<CloudFileSnapshotDTO | null>(null)
  const [selectionLoading, setSelectionLoading] = useState(false)
  const [selectionError, setSelectionError] = useState<string | null>(null)

  // Current directory context (for new file/folder creation)
  const [currentDirectory, setCurrentDirectory] = useState('/')

  // Dialog states
  const [isCreateDirOpen, setIsCreateDirOpen] = useState(false)
  const [isCreateFileOpen, setIsCreateFileOpen] = useState(false)
  const [moveTargetNode, setMoveTargetNode] = useState<CloudNodeDTO | null>(null)
  const [deleteTargetNode, setDeleteTargetNode] = useState<CloudNodeDTO | null>(null)
  const [uploadLoading, setUploadLoading] = useState(false)
  const [uploadToast, setUploadToast] = useState<string | null>(null)

  // Mobile sidebar drawer
  const [isMobileSidebarOpen, setIsMobileSidebarOpen] = useState(false)

  // Stale async response tracking
  const selectionRequestIdRef = useRef(0)
  const fileInputRef = useRef<HTMLInputElement>(null)

  // Fetch directory contents
  const loadDirectory = useCallback(
    async (path: string, markLoading = false) => {
      const norm = normalizePath(path)
      if (markLoading) {
        setLoadingPaths((prev) => new Set(prev).add(norm))
      }
      try {
        const snapshot = await api.getFileSnapshot(norm)
        if (norm === '/') {
          setRootSnapshot(snapshot)
        }
        setDirectoryChildrenMap((prev) => ({
          ...prev,
          [norm]: snapshot.children ?? [],
        }))
        return snapshot
      } catch (err) {
        if (norm === '/') {
          setTreeError(err instanceof Error ? err.message : '加载文件目录失败')
        }
        throw err
      } finally {
        if (markLoading) {
          setLoadingPaths((prev) => {
            const next = new Set(prev)
            next.delete(norm)
            return next
          })
        }
      }
    },
    [api],
  )

  // Refresh tree root and all currently expanded directories
  const refreshTree = useCallback(async () => {
    setTreeLoading(true)
    setTreeError(null)
    try {
      await loadDirectory('/')
      for (const path of expandedPaths) {
        if (path !== '/') {
          void loadDirectory(path)
        }
      }
    } finally {
      setTreeLoading(false)
    }
  }, [expandedPaths, loadDirectory])

  // Select node with stale response protection
  const selectNodeByPath = useCallback(
    async (path: string) => {
      const requestId = ++selectionRequestIdRef.current
      setSelectedPath(path)
      setSelectionLoading(true)
      setSelectionError(null)

      try {
        const snapshot = await api.getFileSnapshot(path)
        // Stale check: ignore response if a newer selection request was made
        if (selectionRequestIdRef.current === requestId) {
          setSelectedSnapshot(snapshot)
          setSelectionLoading(false)
        }
      } catch (err) {
        if (selectionRequestIdRef.current === requestId) {
          setSelectionError(err instanceof Error ? err.message : '加载文件失败')
          setSelectionLoading(false)
        }
      }
    },
    [api],
  )

  // Direct openPath interface (also handles exact artifact paths without enumerating them in tree)
  const openPath = useCallback(
    async (path: string) => {
      const norm = normalizePath(path)
      await selectNodeByPath(norm)
      // If parent is not an artifact path, expand parent directory
      const parent = getParentPath(norm)
      if (parent && !parent.startsWith('/.artifacts')) {
        setExpandedPaths((prev) => new Set(prev).add(parent))
        void loadDirectory(parent)
      }
    },
    [loadDirectory, selectNodeByPath],
  )

  // Expose openPath to caller via ref
  useImperativeHandle(ref, () => ({ openPath }), [openPath])

  // Initial load
  useEffect(() => {
    void loadDirectory('/', false)
    if (initialPath) {
      void openPath(initialPath)
    }
  }, [initialPath, loadDirectory, openPath])

  // Invalidation hook for notification events
  const handleInvalidate = useCallback(() => {
    void refreshTree()
    if (selectedPath) {
      void selectNodeByPath(selectedPath)
    }
  }, [refreshTree, selectNodeByPath, selectedPath])

  useCloudFilesInvalidation(handleInvalidate)

  // Tree item actions
  const handleToggleExpand = (path: string) => {
    setExpandedPaths((prev) => {
      const next = new Set(prev)
      if (next.has(path)) {
        next.delete(path)
      } else {
        next.add(path)
        if (!directoryChildrenMap[path]) {
          void loadDirectory(path, true)
        }
      }
      return next
    })
  }

  const handleSelectNode = (node: CloudNodeDTO) => {
    if (node.kind === 'DIRECTORY') {
      setCurrentDirectory(node.path)
      void selectNodeByPath(node.path)
    } else {
      setCurrentDirectory(getParentPath(node.path))
      void selectNodeByPath(node.path)
    }
    setIsMobileSidebarOpen(false)
  }

  // Dialog actions
  const handleCreateDirectory = async (path: string) => {
    await api.createDirectory({ path })
    const parent = getParentPath(path)
    await loadDirectory(parent)
    setExpandedPaths((prev) => new Set(prev).add(parent))
  }

  const handleCreateFile = async (path: string) => {
    await api.saveText({ path, content: '', expectedRevision: '0' })
    const parent = getParentPath(path)
    await loadDirectory(parent)
    setExpandedPaths((prev) => new Set(prev).add(parent))
    await selectNodeByPath(path)
  }

  const handleMoveNode = async (
    sourcePath: string,
    destinationPath: string,
    expectedVersion: string,
  ) => {
    await api.moveNode({ sourcePath, destinationPath, expectedVersion })
    await refreshTree()
    if (selectedPath === sourcePath) {
      await selectNodeByPath(destinationPath)
    }
  }

  const handleDeleteNode = async (path: string, expectedVersion: string) => {
    await api.deleteNode({ path, expectedVersion })
    await refreshTree()
    if (selectedPath === path) {
      setSelectedPath(null)
      setSelectedSnapshot(null)
    }
  }

  // File upload trigger & execution
  const handleTriggerUpload = () => {
    fileInputRef.current?.click()
  }

  const handleFileInputChange = async (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0]
    if (!file) {
      return
    }
    // reset input so the same file can be chosen again
    e.target.value = ''

    const targetDir = currentDirectory === '/' ? '' : currentDirectory
    const targetPath = `${targetDir}/${file.name}`

    setUploadLoading(true)
    setUploadToast(`正在上传 ${file.name}...`)

    try {
      await api.uploadBlobFile(file, targetPath)
      setUploadToast(`上传成功：${file.name}`)
      await loadDirectory(currentDirectory)
      await selectNodeByPath(targetPath)
      setTimeout(() => setUploadToast(null), 3000)
    } catch (err) {
      setUploadToast(err instanceof Error ? `上传失败: ${err.message}` : '上传失败')
      setTimeout(() => setUploadToast(null), 4000)
    } finally {
      setUploadLoading(false)
    }
  }

  const rootChildren = rootSnapshot?.children ?? []

  return (
    <div className="files-page-container" data-testid="files-page">
      {/* Hidden File Input for Blob Upload */}
      <input
        type="file"
        ref={fileInputRef}
        onChange={handleFileInputChange}
        style={{ display: 'none' }}
        data-testid="cloud-file-input"
        aria-label="选择要上传的文件"
      />

      {/* Top Toolbar */}
      <header className="files-top-toolbar">
        <div className="files-toolbar-group">
          <button
            type="button"
            className="btn-icon mobile-sidebar-toggle"
            onClick={() => setIsMobileSidebarOpen((prev) => !prev)}
            aria-label="切换目录树"
          >
            <Menu size={18} aria-hidden="true" />
          </button>
          <h2 className="files-page-title">
            <Folder size={18} aria-hidden="true" />
            <span>云端文件系统</span>
          </h2>
        </div>

        <div className="files-toolbar-group">
          <button
            type="button"
            className="btn-ghost"
            onClick={() => setIsCreateDirOpen(true)}
            aria-label="新建目录"
            title="在当前目录下新建目录"
          >
            <FolderPlus size={16} aria-hidden="true" />
            <span>新建目录</span>
          </button>
          <button
            type="button"
            className="btn-ghost"
            onClick={() => setIsCreateFileOpen(true)}
            aria-label="新建文件"
            title="在当前目录下新建文本文件"
          >
            <FilePlus size={16} aria-hidden="true" />
            <span>新建文件</span>
          </button>
          <button
            type="button"
            className="btn-ghost"
            onClick={handleTriggerUpload}
            disabled={uploadLoading}
            aria-label="上传文件"
            title="上传文件到当前目录"
          >
            <Upload size={16} aria-hidden="true" />
            <span>{uploadLoading ? '上传中...' : '上传文件'}</span>
          </button>
          <button
            type="button"
            className="btn-icon"
            onClick={refreshTree}
            disabled={treeLoading}
            aria-label="刷新目录树"
            title="刷新"
          >
            <RefreshCw size={16} className={treeLoading ? 'spin' : ''} aria-hidden="true" />
          </button>
        </div>
      </header>

      {/* Toast Notification */}
      {uploadToast && (
        <div className="files-toast-banner" role="status">
          <span>{uploadToast}</span>
        </div>
      )}

      {/* Main Split Layout */}
      <div className="files-layout-split">
        {/* Mobile Backdrop */}
        {isMobileSidebarOpen && (
          <div
            className="files-sidebar-backdrop"
            role="presentation"
            onClick={() => setIsMobileSidebarOpen(false)}
          />
        )}

        {/* Left Column: Directory Tree */}
        <aside className={`files-sidebar ${isMobileSidebarOpen ? 'open' : ''}`}>
          <div className="files-sidebar-actions">
            <span className="files-current-dir" title={`当前目录: ${currentDirectory}`}>
              位置: {currentDirectory}
            </span>
          </div>

          {treeError ? (
            <div className="files-error-state" role="alert">
              <AlertTriangle size={20} aria-hidden="true" />
              <span>{treeError}</span>
              <button type="button" className="btn-ghost" onClick={refreshTree}>
                重试
              </button>
            </div>
          ) : (
            <FilesTree
              rootChildren={rootChildren}
              directoryChildrenMap={directoryChildrenMap}
              expandedPaths={expandedPaths}
              selectedPath={selectedPath}
              loadingPaths={loadingPaths}
              onToggleExpand={handleToggleExpand}
              onSelectNode={handleSelectNode}
              onMoveNode={setMoveTargetNode}
              onDeleteNode={setDeleteTargetNode}
            />
          )}
        </aside>

        {/* Right Column: Selected File Editor / Media Preview */}
        <main className="files-main-content">
          {selectionLoading && (
            <div className="files-loading-state" role="status">
              <RefreshCw className="spin" size={20} aria-hidden="true" />
              <span>正在加载文件内容...</span>
            </div>
          )}

          {selectionError && !selectionLoading && (
            <div className="files-error-state" role="alert">
              <AlertTriangle size={24} aria-hidden="true" />
              <span>{selectionError}</span>
              <button
                type="button"
                className="btn-ghost"
                onClick={() => selectedPath && selectNodeByPath(selectedPath)}
              >
                重试加载
              </button>
            </div>
          )}

          {!selectionLoading && !selectionError && selectedSnapshot && (
            <>
              {selectedSnapshot.node.kind === 'TEXT' && (
                <TextEditor
                  snapshot={selectedSnapshot}
                  api={api}
                  onSaveSuccess={selectNodeByPath}
                  onReload={() => selectNodeByPath(selectedSnapshot.node.path)}
                />
              )}

              {selectedSnapshot.node.kind === 'BLOB' && (
                <BlobPreview node={selectedSnapshot.node} api={api} />
              )}

              {selectedSnapshot.node.kind === 'DIRECTORY' && (
                <div className="files-empty-state" data-testid="directory-summary">
                  <Folder size={48} aria-hidden="true" />
                  <p>目录: {selectedSnapshot.node.path}</p>
                  <span className="files-field-hint">
                    包含 {(selectedSnapshot.children ?? []).length} 个子项
                  </span>
                </div>
              )}
            </>
          )}

          {!selectionLoading && !selectionError && !selectedSnapshot && (
            <div className="files-empty-state" data-testid="files-empty-state">
              <Folder size={48} aria-hidden="true" />
              <p>请在左侧选择文件进行查看或编辑</p>
            </div>
          )}
        </main>
      </div>

      {/* Dialog Modals */}
      <CreateDirectoryModal
        isOpen={isCreateDirOpen}
        currentDirectory={currentDirectory}
        onClose={() => setIsCreateDirOpen(false)}
        onSubmit={handleCreateDirectory}
      />

      <CreateFileModal
        isOpen={isCreateFileOpen}
        currentDirectory={currentDirectory}
        onClose={() => setIsCreateFileOpen(false)}
        onSubmit={handleCreateFile}
      />

      <MoveNodeModal
        isOpen={moveTargetNode !== null}
        node={moveTargetNode}
        onClose={() => setMoveTargetNode(null)}
        onSubmit={handleMoveNode}
      />

      <DeleteConfirmModal
        isOpen={deleteTargetNode !== null}
        node={deleteTargetNode}
        onClose={() => setDeleteTargetNode(null)}
        onSubmit={handleDeleteNode}
      />
    </div>
  )
}
