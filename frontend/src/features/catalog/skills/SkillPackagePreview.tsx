import { useQuery } from '@tanstack/react-query'
import { agentService } from '@/shared/api/agent-service'
import type { SkillPackageDetailDTO, SkillPackageStagingDTO } from '@/shared/api/generated/contracts'
import { formatByteCount as formatBytes } from '@/shared/lib/format-bytes'
import { errorDetailMessage as errorDetail } from '@/shared/lib/user-facing-error'
import { useI18n } from '@/shared/i18n'
import { Badge } from '@/shared/ui/data/Badge'
import { HelpHint } from '@/shared/ui/controls/HelpHint'
import { LoadingSpinner } from '@/shared/ui/feedback/LoadingSpinner'
import {
  addedSkills,
  buildPreviewTree,
  formatCommit,
  removedSkills,
  skillPackageQueryKeys,
  skillReferenceKey,
} from './skill-package-model'
import { SkillPackageTree } from './SkillPackageTree'

/** 全量读取 Agent 技能引用；预览只据此提示，最终发布仍由服务端校验。 */
async function fetchAgentSkillReferences(): Promise<Map<string, string[]>> {
  const references = new Map<string, Set<string>>()
  const agents = await agentService.listAgents()
  for (const agent of agents ?? []) {
    for (const skill of agent.config?.skills ?? []) {
      const key = skillReferenceKey(skill.packageName, skill.name)
      const names = references.get(key) ?? new Set<string>()
      names.add(agent.name)
      references.set(key, names)
    }
  }
  return new Map(
    Array.from(references, ([key, names]) => [key, Array.from(names).sort((a, b) => a.localeCompare(b))]),
  )
}

/**
 * 上传预览：归档摘要、技能清单、文件树与相对当前版本的增删变化。
 * 被移除但仍被 Agent 引用的技能会阻止发布，这里提前暴露而不是等发布失败。
 */
export function SkillPackagePreview({
  preview,
  existing,
  publishError,
  publishing,
}: {
  preview: SkillPackageStagingDTO
  existing: SkillPackageDetailDTO | null
  publishError: string | null
  publishing: boolean
}) {
  const { t } = useI18n()
  const referencesQuery = useQuery({
    queryKey: skillPackageQueryKeys.agentSkillReferences,
    queryFn: fetchAgentSkillReferences,
    staleTime: 30_000,
  })
  const currentSkills = existing?.skills ?? []
  const added = addedSkills(currentSkills, preview.skills)
  const removed = removedSkills(currentSkills, preview.skills)
  const references = referencesQuery.data
  const referenced = references
    ? removed.filter((skill) => references.has(skillReferenceKey(preview.packageName, skill.name)))
    : []
  const tree = buildPreviewTree(preview.packageName, preview.skills, preview.ignoredDirectories)

  return (
    <>
      {publishError ? (
        <div className="form-error-banner" role="alert">
          {publishError}
        </div>
      ) : null}

      <section className="skill-detail-section">
        <div className="skill-section-heading">
          <h3>{t('ai.skillPackages.previewSummary')}</h3>
          <span className="inline-hint">{preview.packageName}</span>
        </div>
        <dl className="skill-facts">
          <div>
            <dt>{t('ai.skillPackages.packageVersion')}</dt>
            <dd>{preview.packageVersion}</dd>
          </div>
          <div>
            <dt>{t('ai.skillPackages.fileCount')}</dt>
            <dd>{preview.fileCount}</dd>
          </div>
          <div>
            <dt>{t('ai.skillPackages.contentSize')}</dt>
            <dd>{formatBytes(preview.contentSizeBytes)}</dd>
          </div>
          <div>
            <dt>{t('ai.skillPackages.archiveSize')}</dt>
            <dd>{formatBytes(preview.archiveSizeBytes)}</dd>
          </div>
          <div>
            <dt>{t('ai.skillPackages.archiveSha')}</dt>
            <dd className="is-mono" title={preview.archiveSha256}>
              {formatCommit(preview.archiveSha256)}
            </dd>
          </div>
        </dl>
      </section>

      <section className="skill-detail-section">
        <div className="skill-section-heading">
          <h3>{t('ai.skillPackages.skills')}</h3>
          <span className="inline-hint">
            {t(
              preview.skills.length === 1
                ? 'ai.skillPackages.skillCountOne'
                : 'ai.skillPackages.skillCountMany',
              { count: preview.skills.length },
            )}
          </span>
        </div>
        <ul className="skill-list">
          {preview.skills.map((skill) => (
            <li key={skill.name}>
              <strong>{skill.name}</strong>
              <span>{skill.description}</span>
            </li>
          ))}
        </ul>
      </section>

      <section className="skill-detail-section">
        <div className="skill-section-heading">
          <h3>{t('ai.skillPackages.fileTree')}</h3>
          <HelpHint label={t('ai.skillPackages.fileTreeHelp')}>
            {t('ai.skillPackages.fileTreeHelpText')}
          </HelpHint>
        </div>
        <SkillPackageTree root={tree} />
        {preview.ignoredDirectories.length > 0 ? (
          <p className="inline-hint">
            {t('ai.skillPackages.ignoredDirectoriesValue', {
              directories: preview.ignoredDirectories.join(', '),
            })}
          </p>
        ) : null}
      </section>

      <section className="skill-detail-section">
        <div className="skill-section-heading">
          <h3>{t('ai.skillPackages.changeSummary')}</h3>
        </div>
        {existing === null ? (
          <p className="inline-hint">{t('ai.skillPackages.newPackageHint')}</p>
        ) : null}
        <div className="skill-change-group">
          <strong>{t('ai.skillPackages.addedSkills')}</strong>
          {added.length > 0 ? (
            <div className="skill-change-tags">
              {added.map((skill) => (
                <Badge tone="success" key={skill.name}>
                  {skill.name}
                </Badge>
              ))}
            </div>
          ) : (
            <span className="inline-hint">{t('ai.skillPackages.noAddedSkills')}</span>
          )}
        </div>
        <div className="skill-change-group">
          <strong>{t('ai.skillPackages.removedSkills')}</strong>
          {removed.length > 0 ? (
            <div className="skill-change-tags">
              {removed.map((skill) => (
                <Badge tone="warning" key={skill.name}>
                  {skill.name}
                </Badge>
              ))}
            </div>
          ) : (
            <span className="inline-hint">{t('ai.skillPackages.noRemovedSkills')}</span>
          )}
        </div>
        <div className="skill-change-group">
          <strong>{t('ai.skillPackages.referencedSkills')}</strong>
          {referencesQuery.isLoading ? (
            <span className="inline-hint">{t('ai.skillPackages.loadingReferences')}</span>
          ) : referencesQuery.isError ? (
            <span className="field-error" role="alert">
              {errorDetail(referencesQuery.error)}
            </span>
          ) : referenced.length > 0 ? (
            <>
              <div className="skill-change-tags">
                {referenced.map((skill) => (
                  <Badge tone="danger" key={skill.name}>
                    {skill.name}
                  </Badge>
                ))}
              </div>
              <p className="field-error" role="alert">
                {t('ai.skillPackages.referencedSkillsHint')}
              </p>
            </>
          ) : (
            <span className="inline-hint">{t('ai.skillPackages.noReferencedSkills')}</span>
          )}
        </div>
        {publishing ? (
          <div className="skill-upload-stage" role="status" aria-live="polite">
            <LoadingSpinner decorative />
            <span>{t('ai.skillPackages.stage.publishing')}</span>
          </div>
        ) : null}
      </section>
    </>
  )
}
