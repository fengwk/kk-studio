import type {
  SkillEnvironmentSyncDTO,
  SkillRefDTO,
} from '@/shared/api/generated/contracts'

/**
 * 技能在执行根所选 Environment 上不可用的原因。
 *
 * 判定与 system prompt 的技能路径选择同源：只有「该 Package 没有失败记录且已安装摘要等于
 * 当前归档摘要」才算该 Environment 已安装当前版本。
 */
export type SkillUnavailableReason = 'NOT_INSTALLED' | 'VERSION_MISMATCH' | 'INSTALL_FAILED'

/** 一项受影响的技能：身份与不可用原因。 */
export interface UnavailableSkill {
  packageName: string
  name: string
  reason: SkillUnavailableReason
}

/**
 * 按 Environment 安装摘要分类 Agent 配置的技能，只返回在该 Environment 拿不到当前版本的技能。
 *
 * `syncByPackage` 必须是全部相关 Package 的权威投影：缺少该 Package 或缺少该 Environment 的行
 * 表示此 Environment 从未上报过它的安装状态，一律按未安装处理，绝不推断成已安装。
 */
export function unavailableSkills(
  skills: SkillRefDTO[],
  environmentId: string,
  syncByPackage: ReadonlyMap<string, SkillEnvironmentSyncDTO[]>,
): UnavailableSkill[] {
  const unavailable: UnavailableSkill[] = []
  for (const skill of skills) {
    const state = syncByPackage
      .get(skill.packageName)
      ?.find((row) => row.environmentId === environmentId)
    const reason = unavailableReason(state)
    if (reason != null) {
      unavailable.push({ packageName: skill.packageName, name: skill.name, reason })
    }
  }
  return unavailable
}

/**
 * 安装失败优先于版本判断：失败时保留的已安装摘要是上一次成功安装的事实，不代表当前可用。
 */
function unavailableReason(
  state: SkillEnvironmentSyncDTO | undefined,
): SkillUnavailableReason | null {
  if (state == null) {
    return 'NOT_INSTALLED'
  }
  if (state.error != null) {
    return 'INSTALL_FAILED'
  }
  if (state.installedSha256 == null) {
    return 'NOT_INSTALLED'
  }
  return state.upToDate ? null : 'VERSION_MISMATCH'
}
