import { ArrowUpCircle, Package, RefreshCw } from 'lucide-react'
import type { SkillPackageDetailDTO, SkillPackageSummaryDTO } from '@/shared/api/generated/contracts'
import { useI18n, type TranslationKey } from '@/shared/i18n'
import { Badge, type BadgeTone } from '@/shared/ui/data/Badge'
import { Button } from '@/shared/ui/controls/Button'
import { ResourceCard, type ResourceCardMetaRow } from '@/shared/ui/cards/ResourceCard'
import {
  deriveCheckStatus,
  displayPackageVersion,
  formatCommit,
  isGitPackage,
  type SkillCheckStatus,
} from './skill-package-model'

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

/**
 * 技能包卡片的元信息行：版本、来源、技能数；Git 包再补检查状态与检查错误，
 * 有技能时追加技能名标签。摘要不含观察值，检查状态取自按包名读回的详情。
 */
function packageRows(
  pkg: SkillPackageSummaryDTO,
  detail: SkillPackageDetailDTO | undefined,
  t: ReturnType<typeof useI18n>['t'],
): ResourceCardMetaRow[] {
  const gitPackage = isGitPackage(pkg)
  const checkStatus = detail && gitPackage ? deriveCheckStatus(detail) : null

  const rows: ResourceCardMetaRow[] = [
    [
      t('ai.skillPackages.packageVersion'),
      <code title={pkg.packageVersion}>{displayPackageVersion(pkg)}</code>,
    ],
    [
      t('ai.skillPackages.sourceLabel'),
      <Badge tone="accent">
        {gitPackage ? t('ai.skillPackages.source.GIT') : t('ai.skillPackages.source.UPLOAD')}
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
  if (pkg.skills.length > 0) {
    rows.push({
      label: t('ai.skillPackages.skills'),
      tags: pkg.skills.map((skill) => skill.name),
      limit: 3,
    })
  }
  return rows
}

/**
 * 主操作按包来源与可更新性决定：
 * 有可更新提交时提供升级，Git 包提供检查，上传包只看详情。
 */
function PrimaryAction(props: {
  pkg: SkillPackageSummaryDTO
  detail: SkillPackageDetailDTO | undefined
  checking: boolean
  updating: boolean
  onUpdate: () => void
  onCheck: () => void
  onDetails: () => void
}) {
  const { pkg, detail, checking, updating, onUpdate, onCheck, onDetails } = props
  const { t } = useI18n()
  const hasUpdate = Boolean(detail?.updateAvailable && detail.headCommit)

  if (hasUpdate) {
    return (
      <Button
        aria-label={`${t('ai.skillPackages.updateTo', {
          commit: formatCommit(detail?.headCommit),
        })} ${pkg.packageName}`}
        disabled={checking}
        loading={updating}
        onClick={onUpdate}
      >
        {!updating && <ArrowUpCircle aria-hidden="true" />}
        {t('ai.skillPackages.updateTo', { commit: formatCommit(detail?.headCommit) })}
      </Button>
    )
  }
  if (isGitPackage(pkg)) {
    return (
      <Button
        aria-label={`${t('ai.skillPackages.check')} ${pkg.packageName}`}
        disabled={updating}
        loading={checking}
        onClick={onCheck}
      >
        {!checking && <RefreshCw aria-hidden="true" />}
        {t('ai.skillPackages.check')}
      </Button>
    )
  }
  return (
    <Button
      aria-label={`${t('ai.skillPackages.detail')} ${pkg.packageName}`}
      onClick={onDetails}
    >
      {t('ai.skillPackages.detail')}
    </Button>
  )
}

export function SkillPackageCard({
  pkg,
  detail,
  checking,
  updating,
  onUpdate,
  onCheck,
  onDetails,
}: {
  pkg: SkillPackageSummaryDTO
  detail: SkillPackageDetailDTO | undefined
  checking: boolean
  updating: boolean
  onUpdate: () => void
  onCheck: () => void
  onDetails: () => void
}) {
  const { t } = useI18n()
  const gitPackage = isGitPackage(pkg)
  return (
    <ResourceCard
      className="skill-package-card"
      icon={<Package aria-hidden="true" />}
      title={pkg.packageName}
      subtitle={pkg.description || '—'}
      meta={packageRows(pkg, detail, t)}
      actions={
        <>
          <PrimaryAction
            pkg={pkg}
            detail={detail}
            checking={checking}
            updating={updating}
            onUpdate={onUpdate}
            onCheck={onCheck}
            onDetails={onDetails}
          />
          {/* Git 包的主操作不是详情，因此详情另给一个入口。 */}
          {gitPackage ? (
            <Button
              variant="ghost"
              size="compact"
              aria-label={`${t('ai.skillPackages.detail')} ${pkg.packageName}`}
              onClick={onDetails}
            >
              {t('ai.skillPackages.detail')}
            </Button>
          ) : null}
        </>
      }
    />
  )
}
