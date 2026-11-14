import { useMutation, useQueryClient } from '@tanstack/react-query'
import { agentService } from '@/shared/api/agent-service'
import type { SkillPackageCreateDTO, SkillPackageDetailDTO } from '@/shared/api/generated/contracts'
import { errorDetailMessage as errorDetail } from '@/shared/lib/user-facing-error'
import { normalizeDescription, skillPackageQueryKeys } from './skill-package-model'

export interface SkillPackageGitInput {
  packageName: string
  description: string
  repositoryUrl: string
  branch: string
  token: string
}

/**
 * Git 来源的创建：一次性提交仓库信息，服务端完成拉取与打包。
 * token 只在填写时以 SET 提交，留空表示不使用凭据。
 */
export function useSkillPackageGitCreate(
  onCreated: (detail: SkillPackageDetailDTO) => void,
  onError: (message: string | null) => void,
) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (input: SkillPackageGitInput) => {
      const payload: SkillPackageCreateDTO = {
        packageName: input.packageName,
        description: normalizeDescription(input.description),
        repositoryUrl: input.repositoryUrl,
        branch: input.branch,
        ...(input.token ? { token: { operation: 'SET', value: input.token } } : {}),
      }
      return agentService.createSkillPackage(payload)
    },
    onSuccess: (detail) => {
      queryClient.setQueryData(skillPackageQueryKeys.detail(detail.packageName), detail)
      void queryClient.invalidateQueries({ queryKey: skillPackageQueryKeys.list })
      onCreated(detail)
    },
    onError: (error: unknown) => onError(errorDetail(error)),
  })
}
