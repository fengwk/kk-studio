import { useState } from 'react'
import {
  ChevronDown,
  ChevronRight,
  Copy,
  Check,
  File,
  FileText,
  Film,
  Folder,
  FolderOpen,
  Image as ImageIcon,
  MoreVertical,
  Music,
  Trash2,
  ArrowRightLeft,
} from 'lucide-react'
import { detectMediaCategory, isArtifactPath } from './files-utils'
import type { CloudNodeDTO } from './types'

interface FilesTreeProps {
  rootChildren: CloudNodeDTO[]
  directoryChildrenMap: Record<string, CloudNodeDTO[]>
  expandedPaths: Set<string>
  selectedPath: string | null
  loadingPaths: Set<string>
  onToggleExpand: (path: string) => void
  onSelectNode: (node: CloudNodeDTO) => void
  onMoveNode: (node: CloudNodeDTO) => void
  onDeleteNode: (node: CloudNodeDTO) => void
}

function getNodeIcon(node: CloudNodeDTO, isExpanded: boolean) {
  if (node.kind === 'DIRECTORY') {
    return isExpanded ? (
      <FolderOpen size={16} className="tree-node-icon dir-icon" aria-hidden="true" />
    ) : (
      <Folder size={16} className="tree-node-icon dir-icon" aria-hidden="true" />
    )
  }
  if (node.kind === 'TEXT') {
    return <FileText size={16} className="tree-node-icon text-icon" aria-hidden="true" />
  }
  const cat = detectMediaCategory(node.mediaType, node.name)
  if (cat === 'image') {
    return <ImageIcon size={16} className="tree-node-icon image-icon" aria-hidden="true" />
  }
  if (cat === 'audio') {
    return <Music size={16} className="tree-node-icon audio-icon" aria-hidden="true" />
  }
  if (cat === 'video') {
    return <Film size={16} className="tree-node-icon video-icon" aria-hidden="true" />
  }
  return <File size={16} className="tree-node-icon file-icon" aria-hidden="true" />
}

export function FilesTree({
  rootChildren,
  directoryChildrenMap,
  expandedPaths,
  selectedPath,
  loadingPaths,
  onToggleExpand,
  onSelectNode,
  onMoveNode,
  onDeleteNode,
}: FilesTreeProps) {
  const [copiedPath, setCopiedPath] = useState<string | null>(null)
  const [activeMenuPath, setActiveMenuPath] = useState<string | null>(null)

  const handleCopyPath = async (path: string, e: React.MouseEvent) => {
    e.stopPropagation()
    try {
      await navigator.clipboard.writeText(path)
      setCopiedPath(path)
      setTimeout(() => setCopiedPath(null), 1500)
    } catch {
      // Ignore clipboard error
    }
  }

  // Render tree item recursively
  const renderItem = (node: CloudNodeDTO, level = 0) => {
    // Ordinary tree NEVER displays /.artifacts or child nodes under /.artifacts
    if (isArtifactPath(node.path)) {
      return null
    }

    const isDirectory = node.kind === 'DIRECTORY'
    const isExpanded = expandedPaths.has(node.path)
    const isSelected = selectedPath === node.path
    const isLoading = loadingPaths.has(node.path)
    const children = directoryChildrenMap[node.path] ?? []
    const isMenuOpen = activeMenuPath === node.path

    return (
      <li
        key={node.path}
        role="treeitem"
        aria-selected={isSelected}
        aria-expanded={isDirectory ? isExpanded : undefined}
        className={`tree-item-wrapper ${isSelected ? 'selected' : ''}`}
      >
        <div
          className={`tree-item-row ${isSelected ? 'selected' : ''}`}
          style={{ paddingLeft: `${Math.max(12, level * 16 + 12)}px` }}
          onClick={() => {
            if (isDirectory) {
              onToggleExpand(node.path)
            }
            onSelectNode(node)
          }}
          onKeyDown={(e) => {
            if (e.key === 'Enter' || e.key === ' ') {
              e.preventDefault()
              if (isDirectory) {
                onToggleExpand(node.path)
              }
              onSelectNode(node)
            }
          }}
          tabIndex={0}
          aria-label={`${isDirectory ? '目录' : '文件'} ${node.name}`}
        >
          {isDirectory ? (
            <button
              type="button"
              className="tree-expand-btn"
              onClick={(e) => {
                e.stopPropagation()
                onToggleExpand(node.path)
              }}
              aria-label={isExpanded ? '折叠' : '展开'}
            >
              {isExpanded ? <ChevronDown size={14} /> : <ChevronRight size={14} />}
            </button>
          ) : (
            <span className="tree-indent-spacer" aria-hidden="true" />
          )}

          {getNodeIcon(node, isExpanded)}

          <span className="tree-item-name" title={node.path}>
            {node.name}
          </span>

          {isLoading && <span className="tree-item-loading">...</span>}

          <div className="tree-item-actions">
            <button
              type="button"
              className="tree-action-btn"
              onClick={(e) => handleCopyPath(node.path, e)}
              title="复制完整路径"
              aria-label={`复制 ${node.name} 完整路径`}
            >
              {copiedPath === node.path ? <Check size={13} /> : <Copy size={13} />}
            </button>

            <div className="tree-menu-dropdown-container">
              <button
                type="button"
                className="tree-action-btn"
                onClick={(e) => {
                  e.stopPropagation()
                  setActiveMenuPath(isMenuOpen ? null : node.path)
                }}
                aria-label={`操作 ${node.name}`}
                title="更多操作"
              >
                <MoreVertical size={13} />
              </button>

              {isMenuOpen && (
                <div
                  className="tree-context-menu"
                  role="menu"
                  onClick={(e) => e.stopPropagation()}
                >
                  <button
                    type="button"
                    role="menuitem"
                    className="context-menu-item"
                    onClick={() => {
                      setActiveMenuPath(null)
                      onMoveNode(node)
                    }}
                  >
                    <ArrowRightLeft size={14} aria-hidden="true" />
                    <span>重命名 / 移动</span>
                  </button>
                  <button
                    type="button"
                    role="menuitem"
                    className="context-menu-item danger"
                    onClick={() => {
                      setActiveMenuPath(null)
                      onDeleteNode(node)
                    }}
                  >
                    <Trash2 size={14} aria-hidden="true" />
                    <span>删除</span>
                  </button>
                </div>
              )}
            </div>
          </div>
        </div>

        {isDirectory && isExpanded && (
          <ul role="group" className="tree-group">
            {children.length === 0 && !isLoading ? (
              <li
                className="tree-empty-folder"
                style={{ paddingLeft: `${(level + 1) * 16 + 28}px` }}
              >
                (空目录)
              </li>
            ) : (
              children
                .filter((child) => !isArtifactPath(child.path))
                .map((child) => renderItem(child, level + 1))
            )}
          </ul>
        )}
      </li>
    )
  }

  // Filter out any root artifact nodes
  const visibleRootNodes = rootChildren.filter((child) => !isArtifactPath(child.path))

  return (
    <nav className="files-tree-nav" aria-label="文件目录树">
      <ul role="tree" className="files-tree-root">
        {visibleRootNodes.length === 0 ? (
          <li className="tree-empty-root">暂无文件</li>
        ) : (
          visibleRootNodes.map((child) => renderItem(child, 0))
        )}
      </ul>
    </nav>
  )
}
