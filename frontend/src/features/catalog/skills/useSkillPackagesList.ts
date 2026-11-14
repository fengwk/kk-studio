import { useMemo } from 'react'
import { useMutation, useQueries, useQuery, useQueryClient } from '@tanstack/react-query'
import { agentService } from '@/shared/api/agent-service'
import type { SkillPackageDetailDTO, SkillPackageSummaryDTO } from '@/shared/api/generated/contracts'
import { errorDetailMessage as errorDetail } from '@/shared/lib/user-facing-error'
import { skillPackageQueryKeys } from './skill-package-model'

/** 按包名/描述过滤列表摘要；空搜索词直接返回全量。 */
function filterPackages(
  packages: SkillPackageSummaryDTO[],
  search: string,
): SkillPackageSummaryDTO[] {
  const term = search.trim().toLowerCase()
  if (!term) {
    return packages
  }
  return packages.filter(
    (pkg) =>
      pkg.packageName.toLowerCase().includes(term)
      || (pkg.description ? pkg.description.toLowerCase().includes(term) : false),
  )
}

/**
 * 技能包列表页的数据面：列表查询、按包名读回的详情（卡片要用的检查观察值）、
 * 检查与升级两个写入动作，以及写入后统一的列表失效。
 */
export function useSkillPackagesList(search: string) {
  const queryClient = useQueryClient()

  const packagesQuery = useQuery({
    queryKey: skillPackageQueryKeys.list,
    queryFn: () => agentService.listSkillPackages(),
  })

  const filteredPackages = useMemo(
    () => filterPackages(packagesQuery.data ?? [], search),
    [packagesQuery.data, search],
  )

  // 列表摘要不含检查观察值：按包名读取详情，供卡片呈现状态。
  const detailQueries = useQueries({
    queries: filteredPackages.map((pkg) => ({
      queryKey: skillPackageQueryKeys.detail(pkg.packageName),
      queryFn: () => agentService.getSkillPackage(pkg.packageName),
      staleTime: 30_000,
    })),
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

  function invalidatePackages() {
    void queryClient.invalidateQueries({ queryKey: skillPackageQueryKeys.list })
  }

  return { packagesQuery, filteredPackages, detailsByName, invalidatePackages }
}

/** 检查与升级共享同一套失败呈现：动作错误展示在页面级 banner，成功后回读列表。 */
export function useSkillPackageActions(
  invalidatePackages: () => void,
  setActionError: (message: string | null) => void,
) {
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

  return { checkMutation, publishMutation }
}
