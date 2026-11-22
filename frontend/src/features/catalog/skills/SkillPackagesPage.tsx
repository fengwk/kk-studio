import { useState } from 'react'
import { AiConsoleFrame } from '@/app/ai-console/AiConsoleFrame'
import { useI18n } from '@/shared/i18n'
import { ResourceGrid } from '@/shared/ui/cards/ResourceGrid'
import { CreateCard } from '@/shared/ui/feedback/CreateCard'
import { SkillPackageCreateModal } from './SkillPackageCreateModal'
import { SkillPackageDetailModal } from './SkillPackageDetailModal'
import { SkillPackageEditModal } from './SkillPackageEditModal'
import { SkillPackageCard } from './SkillPackageCard'
import { useSkillPackageActions, useSkillPackagesList } from './useSkillPackagesList'
import './skill-packages.css'

/**
 * 技能包列表页：编排搜索、三个弹窗的打开状态与卡片动作。
 * 数据读取、检查/升级写入与卡片呈现分别在 `useSkillPackagesList`、`SkillPackageCard` 中。
 */
export function SkillPackagesPage() {
  const { t } = useI18n()
  const [search, setSearch] = useState('')
  const [createOpen, setCreateOpen] = useState(false)
  const [detailName, setDetailName] = useState<string | null>(null)
  const [editName, setEditName] = useState<string | null>(null)
  const [actionError, setActionError] = useState<string | null>(null)

  const { packagesQuery, filteredPackages, detailsByName, invalidatePackages } =
    useSkillPackagesList(search)
  const { checkMutation, publishMutation } = useSkillPackageActions(
    invalidatePackages,
    setActionError,
  )

  /** 升级需要有已读回的详情：提交当前版本与目标提交，服务端做 CAS。 */
  function updateToHead(name: string) {
    const detail = detailsByName.get(name)
    if (!detail?.headCommit) {
      return
    }
    publishMutation.mutate({
      name,
      expectedVersion: detail.version,
      targetCommit: detail.headCommit,
    })
  }

  function checkNow(name: string) {
    checkMutation.mutate({
      name,
      expectedVersion: detailsByName.get(name)?.version ?? versionOf(name),
    })
  }

  /** 详情尚未读回时回落到列表摘要里的版本。 */
  function versionOf(name: string): string {
    return filteredPackages.find((pkg) => pkg.packageName === name)?.version ?? ''
  }

  const content = (
    <ResourceGrid>
      <CreateCard
        title={t('ai.skillPackages.create')}
        subtitle={t('ai.skillPackages.description')}
        onClick={() => setCreateOpen(true)}
      />
      {filteredPackages.map((pkg) => (
        <SkillPackageCard
          key={pkg.packageName}
          pkg={pkg}
          detail={detailsByName.get(pkg.packageName)}
          checking={checkMutation.isPending && checkMutation.variables?.name === pkg.packageName}
          updating={publishMutation.isPending && publishMutation.variables?.name === pkg.packageName}
          onUpdate={() => updateToHead(pkg.packageName)}
          onCheck={() => checkNow(pkg.packageName)}
          onDetails={() => setDetailName(pkg.packageName)}
        />
      ))}
    </ResourceGrid>
  )

  return (
    <AiConsoleFrame
      search={search}
      onSearchChange={setSearch}
      busy={packagesQuery.isLoading}
      error={packagesQuery.error}
      onRetry={() => void packagesQuery.refetch()}
      mutationError={actionError ? new Error(actionError) : null}
      content={content}
    >
      {createOpen && (
        <SkillPackageCreateModal
          onClose={() => setCreateOpen(false)}
          onCreated={() => {
            setCreateOpen(false)
            invalidatePackages()
          }}
        />
      )}

      {detailName && (
        <SkillPackageDetailModal
          packageName={detailName}
          onClose={() => setDetailName(null)}
          onEdit={() => setEditName(detailName)}
          onDeleted={() => {
            setDetailName(null)
            invalidatePackages()
          }}
        />
      )}

      {editName && (
        <SkillPackageEditModal
          packageName={editName}
          onClose={() => setEditName(null)}
          onSuccess={() => {
            setEditName(null)
            invalidatePackages()
          }}
        />
      )}
    </AiConsoleFrame>
  )
}
