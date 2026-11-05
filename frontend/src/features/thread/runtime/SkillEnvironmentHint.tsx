import { useQueries, useQueryClient } from '@tanstack/react-query'
import { useI18n } from '@/shared/i18n'
import { useResourceSync } from '@/shared/app-events/useResourceSync'
import { agentService } from '@/shared/api/agent-service'
import { queryKeys } from '@/shared/lib/query-keys'
import { unavailableSkills } from '@/features/thread/runtime/skill-availability'
import type {
  AgentDefinitionDTO,
  EnvironmentCardDTO,
  SkillEnvironmentSyncDTO,
} from '@/shared/api/generated/contracts'
import '@/features/thread/runtime/skill-environment-hint.css'

/**
 * 执行根技能可用性提示：所选 Environment 拿不到 Agent 技能的当前版本时，列出受影响的技能与原因。
 *
 * 两个事实都来自既有契约：Agent 配置的 `config.skills` 与每个 Skill Package 的环境安装摘要
 * `GET /skill-packages/{name}/environments`。本组件不推断安装事实，任一 Package 摘要尚未就绪
 * 或读取失败时不出提示；Environment 推送的 changed 只提示回读摘要，切换 Environment 或安装完成
 * 后提示随之消失。
 */
export function SkillEnvironmentHint({
  agentName,
  environmentName,
  agents,
  environments,
}: {
  /** 执行根生效的 Agent 名；null 表示分支尚未就绪。 */
  agentName: string | null
  /** 执行根生效的 Environment 名；null 表示未选择环境。 */
  environmentName: string | null
  agents: AgentDefinitionDTO[]
  environments: EnvironmentCardDTO[]
}) {
  const { t } = useI18n()
  const queryClient = useQueryClient()
  const environmentId = environmentName == null
    ? null
    : environments.find((environment) => environment.name === environmentName)?.id ?? null
  const skills = agentName == null
    ? []
    : agents.find((agent) => agent.name === agentName)?.config.skills ?? []
  const packageNames = [...new Set(skills.map((skill) => skill.packageName))]
  const needed = environmentId != null && packageNames.length > 0
  const syncQueries = useQueries({
    queries: packageNames.map((packageName) => ({
      queryKey: queryKeys.skills.packageEnvironmentSync(packageName),
      queryFn: () => agentService.listSkillPackageEnvironments(packageName),
      enabled: needed,
    })),
  })
  useResourceSync(needed ? { kind: 'environments' } : null, {
    sync: () => {
      void queryClient.invalidateQueries({ queryKey: queryKeys.skills.environmentSync })
    },
    syncOn: (name) => name === 'changed',
  })

  const resolved = syncQueries.every((query) => query.isSuccess)
  if (!needed || environmentId == null || !resolved) {
    return null
  }
  const syncByPackage = new Map<string, SkillEnvironmentSyncDTO[]>()
  syncQueries.forEach((query, index) => {
    if (query.data != null) {
      syncByPackage.set(packageNames[index], query.data)
    }
  })
  const affected = unavailableSkills(skills, environmentId, syncByPackage)
  if (affected.length === 0) {
    return null
  }
  return (
    <section
      className="thread-skill-hint"
      data-testid="thread-skill-hint"
      aria-label={t('ai.runtime.skillAvailability.title')}
    >
      <p className="thread-skill-hint-title">{t('ai.runtime.skillAvailability.title')}</p>
      <ul className="thread-skill-hint-list">
        {affected.map((skill) => (
          <li key={`${skill.packageName}/${skill.name}`} className="thread-skill-hint-item">
            <span className="thread-skill-hint-skill">
              {t('ai.runtime.skillAvailability.skillLabel', {
                package: skill.packageName,
                skill: skill.name,
              })}
            </span>
            <span className="thread-skill-hint-reason">
              {t(`ai.runtime.skillAvailability.reason.${skill.reason}`)}
            </span>
          </li>
        ))}
      </ul>
      <p className="thread-skill-hint-description">
        {t('ai.runtime.skillAvailability.description')}
      </p>
    </section>
  )
}
