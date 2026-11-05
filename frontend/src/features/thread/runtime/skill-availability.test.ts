import { describe, expect, it } from 'vitest'
import { unavailableSkills } from '@/features/thread/runtime/skill-availability'
import type {
  SkillEnvironmentSyncDTO,
  SkillRefDTO,
} from '@/shared/api/generated/contracts'

const ENVIRONMENT_ID = 'e1000000-0000-4000-8000-0000000000e1'
const OTHER_ENVIRONMENT_ID = 'e1000000-0000-4000-8000-0000000000e2'
const CURRENT_SHA = 'a'.repeat(64)
const PREVIOUS_SHA = 'b'.repeat(64)

function sync(overrides: Partial<SkillEnvironmentSyncDTO> = {}): SkillEnvironmentSyncDTO {
  return {
    environmentId: ENVIRONMENT_ID,
    installedSha256: CURRENT_SHA,
    localPath: '/home/dev/.kkstudio/skills/dev-tools',
    error: null,
    upToDate: true,
    ...overrides,
  }
}

const devSkill: SkillRefDTO = { packageName: 'dev-tools', name: 'dev' }
const reviewSkill: SkillRefDTO = { packageName: 'dev-tools', name: 'review' }
const deploySkill: SkillRefDTO = { packageName: 'ops', name: 'deploy' }

describe('unavailableSkills', () => {
  // 已安装当前版本的技能不进入提示，未安装、版本不一致与安装失败各自给出独立原因。
  it('classifies every skill of the selected environment by install state', () => {
    const result = unavailableSkills(
      [devSkill, reviewSkill, deploySkill, { packageName: 'ops', name: 'rollback' }],
      ENVIRONMENT_ID,
      new Map([
        ['dev-tools', [sync()]],
        ['ops', [sync({ installedSha256: PREVIOUS_SHA, upToDate: false })]],
      ]),
    )

    expect(result).toEqual([
      { packageName: 'ops', name: 'deploy', reason: 'VERSION_MISMATCH' },
      { packageName: 'ops', name: 'rollback', reason: 'VERSION_MISMATCH' },
    ])
  })

  // 缺少 Package 投影或缺少该环境行都是从未上报安装状态，必须按未安装提示而不是当作可用。
  it('treats a missing package or environment row as not installed', () => {
    const result = unavailableSkills(
      [devSkill, deploySkill],
      ENVIRONMENT_ID,
      new Map([
        ['dev-tools', [sync({ environmentId: OTHER_ENVIRONMENT_ID })]],
      ]),
    )

    expect(result).toEqual([
      { packageName: 'dev-tools', name: 'dev', reason: 'NOT_INSTALLED' },
      { packageName: 'ops', name: 'deploy', reason: 'NOT_INSTALLED' },
    ])
  })

  // 同步失败会保留上一次成功安装的摘要；此时它不等于当前版本的可用事实，原因必须是安装失败。
  it('reports install failure even when the previous installed digest matches the artifact', () => {
    const result = unavailableSkills(
      [devSkill],
      ENVIRONMENT_ID,
      new Map([['dev-tools', [sync({ error: 'git fetch failed' })]]]),
    )

    expect(result).toEqual([
      { packageName: 'dev-tools', name: 'dev', reason: 'INSTALL_FAILED' },
    ])
  })

  // 未配置技能时不产生任何提示内容。
  it('returns nothing when the agent configures no skills', () => {
    expect(unavailableSkills([], ENVIRONMENT_ID, new Map())).toEqual([])
  })
})
