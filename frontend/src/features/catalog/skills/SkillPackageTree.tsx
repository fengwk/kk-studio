import { FileArchive, FileText, FolderTree } from 'lucide-react'
import { useI18n } from '@/shared/i18n'
import { Badge } from '@/shared/ui/data/Badge'
import { formatByteCount as formatBytes } from '@/shared/lib/format-bytes'
import type { SkillTreeNode } from './skill-package-model'
import './skill-packages.css'

/** 归档目录树；被忽略的目录标记出来，文件展示解压后大小。 */
export function SkillPackageTree({ root }: { root: SkillTreeNode }) {
  const { t } = useI18n()

  function renderNode(node: SkillTreeNode, depth: number) {
    const isDirectory = node.kind === 'directory'
    return (
      <li key={`${depth}-${node.name}`} className={node.ignored ? 'skill-tree-item is-ignored' : 'skill-tree-item'}>
        <span className={isDirectory ? 'skill-tree-directory' : 'skill-tree-file'}>
          {depth === 0 ? (
            <FileArchive aria-hidden="true" />
          ) : isDirectory ? (
            <FolderTree aria-hidden="true" />
          ) : (
            <FileText aria-hidden="true" />
          )}
          <span className="skill-tree-name">{node.name}</span>
          {node.ignored ? <Badge tone="warning">{t('ai.skillPackages.ignoredBadge')}</Badge> : null}
          {node.sizeBytes ? (
            <span className="skill-tree-size">{formatBytes(node.sizeBytes)}</span>
          ) : null}
        </span>
        {node.children && node.children.length > 0 ? (
          <ul className="skill-tree">
            {node.children.map((child) => renderNode(child, depth + 1))}
          </ul>
        ) : null}
      </li>
    )
  }

  return <ul className="skill-tree skill-tree-root">{renderNode(root, 0)}</ul>
}
