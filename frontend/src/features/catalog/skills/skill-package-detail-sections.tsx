import type { SkillPackageDetailDTO, SkillPackageFileDTO } from '@/shared/api/generated/contracts'
import { Download } from 'lucide-react'
import { formatByteCount as formatBytes } from '@/shared/lib/format-bytes'
import { formatBackendDate } from '@/shared/lib/date-format'
import { errorDetailMessage as errorDetail } from '@/shared/lib/user-facing-error'
import { useI18n } from '@/shared/i18n'
import { Badge } from '@/shared/ui/data/Badge'
import { Button } from '@/shared/ui/controls/Button'
import {
  buildFileTree,
  displayPackageVersion,
  formatCommit,
  isGitPackage,
} from './skill-package-model'
import { SkillPackageTree } from './SkillPackageTree'

/** 摘要事实表：版本、发布时间、技能数、归档大小与摘要；Git 来源额外补仓库、分支与令牌状态。 */
export function SkillPackageSummarySection({
  detail,
  downloadPending,
  onDownload,
  onEdit,
  downloadError,
}: {
  detail: SkillPackageDetailDTO
  downloadPending: boolean
  onDownload: () => void
  onEdit: () => void
  downloadError: unknown
}) {
  const { t } = useI18n()
  return (
    <section className="skill-detail-section">
      <div className="skill-section-heading">
        <h3>{t('ai.skillPackages.summary')}</h3>
        <Badge tone="accent">
          {isGitPackage(detail) ? t('ai.skillPackages.source.GIT') : t('ai.skillPackages.source.UPLOAD')}
        </Badge>
      </div>
      <dl className="skill-facts">
        <div>
          <dt>{t('ai.skillPackages.packageVersion')}</dt>
          <dd className="is-mono" title={detail.packageVersion}>
            {displayPackageVersion(detail)}
          </dd>
        </div>
        <div>
          <dt>{t('ai.skillPackages.publishedAt')}</dt>
          <dd>{formatBackendDate(detail.publishedAt)}</dd>
        </div>
        <div>
          <dt>{t('ai.skillPackages.skillsCount')}</dt>
          <dd>{detail.skills.length}</dd>
        </div>
        <div>
          <dt>{t('ai.skillPackages.archiveSize')}</dt>
          <dd>{formatBytes(detail.artifactSizeBytes)}</dd>
        </div>
        <div>
          <dt>{t('ai.skillPackages.archiveSha')}</dt>
          <dd className="is-mono" title={detail.artifactSha256}>
            {formatCommit(detail.artifactSha256)}
          </dd>
        </div>
        {detail.git ? (
          <>
            <div>
              <dt>{t('ai.skillPackages.repositoryUrl')}</dt>
              <dd className="is-wrap">{detail.git.repositoryUrl}</dd>
            </div>
            <div>
              <dt>{t('ai.skillPackages.branch')}</dt>
              <dd className="is-mono">{detail.git.branch}</dd>
            </div>
            <div>
              <dt>{t('ai.skillPackages.token')}</dt>
              <dd>
                {detail.git.token.configured
                  ? t('ai.skillPackages.tokenConfigured')
                  : t('ai.skillPackages.tokenNotConfigured')}
              </dd>
            </div>
            <div>
              <dt>{t('ai.skillPackages.observedHeadCommit')}</dt>
              <dd className="is-mono" title={detail.headCommit ?? undefined}>
                {formatCommit(detail.headCommit)}
              </dd>
            </div>
          </>
        ) : null}
      </dl>
      {detail.headCheckError ? (
        <p className="field-error" role="alert">
          {detail.headCheckError}
        </p>
      ) : null}
      <div className="skill-detail-actions">
        <Button variant="secondary" loading={downloadPending} onClick={onDownload}>
          {!downloadPending && <Download aria-hidden="true" />}
          {t('ai.skillPackages.downloadArchive')}
        </Button>
        <Button variant="secondary" onClick={onEdit}>
          {t('ai.skillPackages.edit')}
        </Button>
      </div>
      {downloadError ? (
        <p className="field-error" role="alert">
          {errorDetail(downloadError)}
        </p>
      ) : null}
    </section>
  )
}

export function SkillPackageSkillsSection({ detail }: { detail: SkillPackageDetailDTO }) {
  const { t } = useI18n()
  return (
    <section className="skill-detail-section">
      <div className="skill-section-heading">
        <h3>{t('ai.skillPackages.skills')}</h3>
        <span className="inline-hint">
          {t(
            detail.skills.length === 1
              ? 'ai.skillPackages.skillCountValue.one'
              : 'ai.skillPackages.skillCountValue.other',
            { count: detail.skills.length },
          )}
        </span>
      </div>
      <ul className="skill-list">
        {detail.skills.map((skill) => (
          <li key={skill.name}>
            <strong>{skill.name}</strong>
            <span>{skill.description}</span>
          </li>
        ))}
      </ul>
    </section>
  )
}

/** 归档文件树：只在平台已经给出归档清单时可构建。 */
export function SkillPackageFilesSection({
  packageName,
  files,
  loading,
}: {
  packageName: string
  files: SkillPackageFileDTO[] | undefined
  loading: boolean
}) {
  const { t } = useI18n()
  const tree = files ? buildFileTree(packageName, files) : null
  return (
    <section className="skill-detail-section">
      <div className="skill-section-heading">
        <h3>{t('ai.skillPackages.fileTree')}</h3>
        <span className="inline-hint">
          {t(
            (files?.length ?? 0) === 1
              ? 'ai.skillPackages.fileCountValue.one'
              : 'ai.skillPackages.fileCountValue.other',
            { count: files?.length ?? 0 },
          )}
        </span>
      </div>
      {loading ? (
        <p className="inline-hint">{t('ai.skillPackages.loadingFiles')}</p>
      ) : tree ? (
        <SkillPackageTree root={tree} />
      ) : null}
    </section>
  )
}
