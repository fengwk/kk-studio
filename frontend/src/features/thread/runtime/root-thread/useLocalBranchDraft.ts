import { useEffect, useRef, useState } from 'react'
import {
  materializeBlankBranchDraft,
  type BranchDraft,
} from '@/features/thread/branch-draft'
import { isBoundTarget } from '@/features/thread/runtime/agent-pane'
import type { PaneTarget } from '@/features/thread/runtime/agent-pane'
import type {
  AgentDTO,
  EnvironmentDTO,
  ModelDTO,
} from '@/shared/api/generated/contracts'

/**
 * 未绑定 Thread 的本地分支草稿：默认从偏好 Agent 物化，用户改动后才算「已编辑」。
 *
 * <p>绑定 Thread 时草稿由 snapshot 权威投影提供，本地草稿不参与；ref 保持同步镜像，
 * 供验收/命令路径在 state commit 之前读取。
 */
export function useLocalBranchDraft({
  target,
  agents,
  environments: _environments,
  defaults,
  models,
}: {
  target: PaneTarget
  agents: AgentDTO[]
  environments: EnvironmentDTO[]
  defaults: {
    agentName?: string
    yoloEnabled?: boolean
    environmentName?: string | null
  }
  models: ModelDTO[]
}) {
  const [localDraft, setLocalDraft] = useState<BranchDraft | null>(null)
  // 未创建分支草稿是否被用户真的改过：只有存在 diff 才展示 draft 状态，默认不做常驻噪声。
  const [localDraftEdited, setLocalDraftEdited] = useState(false)
  const localDraftRef = useRef<BranchDraft | null>(localDraft)

  useEffect(() => {
    localDraftRef.current = localDraft
  }, [localDraft])

  useEffect(() => {
    if (isBoundTarget(target)) {
      return
    }
    if (localDraft != null || models.length === 0) {
      return
    }
    const preferred = defaults.agentName
      ? agents.find((agent) => agent.name === defaults.agentName)
      : agents.find((agent) =>
        materializeBlankBranchDraft(
          agent,
          defaults.yoloEnabled ?? false,
          models,
          defaults.environmentName,
        ) != null,
      )
    const materialized = preferred == null
      ? null
      : materializeBlankBranchDraft(
        preferred,
        defaults.yoloEnabled ?? false,
        models,
        defaults.environmentName,
      )
    if (materialized != null) {
      setLocalDraft(materialized)
    }
  }, [agents, defaults.agentName, defaults.environmentName, defaults.yoloEnabled, localDraft, models, target])

  return {
    localDraft,
    setLocalDraft,
    localDraftEdited,
    setLocalDraftEdited,
    localDraftRef,
  }
}
