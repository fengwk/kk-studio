import { useMemo, useState } from 'react'
import { useMutation, useQueries, useQuery, useQueryClient } from '@tanstack/react-query'
import { ArrowUpCircle, Package, RefreshCw } from 'lucide-react'
import { AiConsoleFrame } from '@/app/ai-console/AiConsoleFrame'
import { agentService } from '@/shared/api/agent-service'
import { environmentService } from '@/features/environment/environment-service'
import type { SkillPackageDetailDTO } from '@/shared/api/generated/contracts'
import { queryKeys } from '@/shared/lib/query-keys'
import { errorDetailMessage as errorDetail } from '@/shared/lib/user-facing-error'
import { useI18n, type TranslationKey } from '@/shared/i18n'
import { Badge, type BadgeTone } from '@/shared/ui/data/Badge'
import { Button } from '@/shared/ui/controls/Button'
import { ResourceCard, type ResourceCardMetaRow } from '@/shared/ui/cards/ResourceCard'
import { ResourceGrid } from '@/shared/ui/cards/ResourceGrid'
import { CreateCard } from '@/shared/ui/feedback/CreateCard'
import { SkillPackageCreateModal } from './SkillPackageCreateModal'
import { SkillPackageDetailModal } from './SkillPackageDetailModal'
import { SkillPackageEditModal } from './SkillPackageEditModal'
import {
  deriveCheckStatus,
  displayPackageVersion,
  environmentSyncStatus,
  formatCommit,
  isGitPackage,
  skillPackageQueryKeys,
  type SkillCheckStatus,
} from './skill-package-model'
import './skill-packages.css'

const CHECK_LABEL_KEYS: Record<SkillCheckStatus, TranslationKey> = {
  UNCHECKED: 'ai.skillPackages.status.UNCHECKED',
  UP_TO_DATE: 'ai.skillPackages.status.UP_TO_DATE',
  UPDATE_AVAILABLE: 'ai.skillPackages.status.UPDATE_AVAILABLE',
  CHECK_FAILED: 'ai.skillPackages.status.CHECK_FAILED',
}

const CHECK_TONES: Record<SkillCheckStatus, BadgeTone> = {
  UNCHECKED: 'neutral',
  UP_TO_DATE: 'success',
  UPDATE_AVAILABLE: 'warning',
  CHECK_FAILED: 'danger',
}

export function SkillPackagesPage() {
  const { t } = useI18n()
  const queryClient = useQueryClient()
  const [search, setSearch] = useState('')
  const [createOpen, setCreateOpen] = useState(false)
  const [detailName, setDetailName] = useState<string | null>(null)
  const [editName, setEditName] = useState<string | null>(null)
  const [actionError, setActionError] = useState<string | null>(null)

  const packagesQuery = useQuery({
    queryKey: skillPackageQueryKeys.list,
    queryFn: () => agentService.listSkillPackages(),
  })

  const filteredPackages = useMemo(() => {
    const packages = packagesQuery.data ?? []
    const term = search.trim().toLowerCase()
    if (!term) {
      return packages
    }
    return packages.filter(
      (pkg) =>
        pkg.packageName.toLowerCase().includes(term)
        || (pkg.description ? pkg.description.toLowerCase().includes(term) : false),
    )
  }, [packagesQuery.data, search])

  // 列表摘要不含检查观察值与环境安装状态：按包名读取详情与环境投影，供卡片呈现状态。
  const detailQueries = useQueries({
    queries: filteredPackages.map((pkg) => ({
      queryKey: skillPackageQueryKeys.detail(pkg.packageName),
      queryFn: () => agentService.getSkillPackage(pkg.packageName),
      staleTime: 30_000,
    })),
  })
  const environmentQueries = useQueries({
    queries: filteredPackages.map((pkg) => ({
      queryKey: skillPackageQueryKeys.environments(pkg.packageName),
      queryFn: () => agentService.listSkillPackageEnvironments(pkg.packageName),
      staleTime: 15_000,
    })),
  })
  const environmentListQuery = useQuery({
    queryKey: queryKeys.environments.list,
    queryFn: () => environmentService.listEnvironments(),
  })

  const detailsByName = useMemo(() => {
    const map = new Map<string, SkillPackageDetailDTO>()
    filteredPackages.forEach((pkg, index) => {
      const detail = detailQueries[index]?.data
      if (detail) {
        map.set(pkg.packageName, detail)
      }
    })
    return map
  }, [filteredPackages, detailQueries])

  const failedEnvironmentsByName = useMemo(() => {
    const onlineEnvironments = new Map(
      (environmentListQuery.data ?? []).map((environment) => [environment.id, environment]),
    )
    const map = new Map<string, string[]>()
    filteredPackages.forEach((pkg, index) => {
      const sync = environmentQueries[index]?.data ?? []
      const failed = sync
        .filter((environment) => environmentSyncStatus(environment) === 'FAILED')
        .filter((environment) => onlineEnvironments.get(environment.environmentId)?.ready === true)
        .map(
          (environment) =>
            onlineEnvironments.get(environment.environmentId)?.name ?? environment.environmentId,
        )
      if (failed.length > 0) {
        map.set(pkg.packageName, failed)
      }
    })
    return map
  }, [environmentListQuery.data, environmentQueries, filteredPackages])

  const invalidatePackages = () => {
    void queryClient.invalidateQueries({ queryKey: skillPackageQueryKeys.list })
  }

  const checkMutation = useMutation({
    mutationFn: ({ name, expectedVersion }: { name: string; expectedVersion: string }) =>
      agentService.checkSkillPackage(name, { expectedVersion }),
    onSuccess: () => {
      setActionError(null)
      invalidatePackages()
    },
    onError: (error: unknown) => setActionError(errorDetail(error)),
  })

  const publishMutation = useMutation({
    mutationFn: ({
      name,
      expectedVersion,
      targetCommit,
    }: {
      name: string
      expectedVersion: string
      targetCommit: string
    }) => agentService.publishSkillPackage(name, { expectedVersion, targetCommit }),
    onSuccess: () => {
      setActionError(null)
      invalidatePackages()
    },
    onError: (error: unknown) => setActionError(errorDetail(error)),
  })

  const content = (
    <ResourceGrid>
      <CreateCard
        title={t('ai.skillPackages.create')}
        subtitle={t('ai.skillPackages.description')}
        onClick={() => setCreateOpen(true)}
      />
      {filteredPackages.map((pkg) => {
        const detail = detailsByName.get(pkg.packageName)
        const gitPackage = isGitPackage(pkg)
        const checkStatus = detail && gitPackage ? deriveCheckStatus(detail) : null
        const hasUpdate = Boolean(detail?.updateAvailable && detail.headCommit)
        const failedEnvironments = failedEnvironmentsByName.get(pkg.packageName) ?? []
        const checking = checkMutation.isPending
          && checkMutation.variables?.name === pkg.packageName
        const updating = publishMutation.isPending
          && publishMutation.variables?.name === pkg.packageName

        const rows: ResourceCardMetaRow[] = [
          [
            t('ai.skillPackages.packageVersion'),
            <code title={pkg.packageVersion}>{displayPackageVersion(pkg)}</code>,
          ],
          [
            t('ai.skillPackages.sourceLabel'),
            <Badge tone="accent">
              {gitPackage
                ? t('ai.skillPackages.source.GIT')
                : t('ai.skillPackages.source.UPLOAD')}
            </Badge>,
          ],
          [t('ai.skillPackages.skillsCount'), String(pkg.skills.length)],
        ]
        if (gitPackage) {
          rows.push([
            t('ai.skillPackages.checkStatus'),
            checkStatus ? (
              <Badge tone={CHECK_TONES[checkStatus]} data-testid="check-status-pill">
                {t(CHECK_LABEL_KEYS[checkStatus])}
              </Badge>
            ) : (
              '—'
            ),
          ])
        }
        if (detail?.headCheckError) {
          rows.push({
            label: t('shared.error'),
            value: (
              <span className="inline-hint danger" role="alert">
                {detail.headCheckError}
              </span>
            ),
            wrap: true,
          })
        }
        if (failedEnvironments.length > 0) {
          rows.push({
            label: t('ai.skillPackages.environmentSync'),
            value: (
              <span className="inline-hint danger" role="alert">
                {t('ai.skillPackages.environmentSyncFailed', {
                  names: failedEnvironments.join(', '),
                })}
              </span>
            ),
            wrap: true,
          })
        }
        if (pkg.skills.length > 0) {
          rows.push({
            label: t('ai.skillPackages.skills'),
            tags: pkg.skills.map((skill) => skill.name),
            limit: 3,
          })
        }

        const primaryAction = hasUpdate ? (
          <Button
            aria-label={`${t('ai.skillPackages.updateTo', {
              commit: formatCommit(detail?.headCommit),
            })} ${pkg.packageName}`}
            disabled={checking}
            loading={updating}
            onClick={() =>
              publishMutation.mutate({
                name: pkg.packageName,
                expectedVersion: detail!.version,
                targetCommit: detail!.headCommit!,
              })
            }
          >
            {!updating && <ArrowUpCircle aria-hidden="true" />}
            {t('ai.skillPackages.updateTo', { commit: formatCommit(detail?.headCommit) })}
          </Button>
        ) : gitPackage ? (
          <Button
            aria-label={`${t('ai.skillPackages.check')} ${pkg.packageName}`}
            disabled={updating}
            loading={checking}
            onClick={() =>
              checkMutation.mutate({
                name: pkg.packageName,
                expectedVersion: detail?.version ?? pkg.version,
              })
            }
          >
            {!checking && <RefreshCw aria-hidden="true" />}
            {t('ai.skillPackages.check')}
          </Button>
        ) : (
          <Button
            aria-label={`${t('ai.skillPackages.detail')} ${pkg.packageName}`}
            onClick={() => setDetailName(pkg.packageName)}
          >
            {t('ai.skillPackages.detail')}
          </Button>
        )

        return (
          <ResourceCard
            key={pkg.packageName}
            className="skill-package-card"
            icon={<Package aria-hidden="true" />}
            title={pkg.packageName}
            subtitle={pkg.description || '—'}
            meta={rows}
            actions={
              <>
                {primaryAction}
                {gitPackage ? (
                  <Button
                    variant="ghost"
                    size="compact"
                    aria-label={`${t('ai.skillPackages.detail')} ${pkg.packageName}`}
                    onClick={() => setDetailName(pkg.packageName)}
                  >
                    {t('ai.skillPackages.detail')}
                  </Button>
                ) : null}
              </>
            }
          />
        )
      })}
    </ResourceGrid>
  )

  return (
    <AiConsoleFrame
      search={search}
      onSearchChange={setSearch}
      busy={packagesQuery.isLoading}
      error={packagesQuery.error}
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
