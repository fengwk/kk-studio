import { includesSearch, naturalNameCompare } from '@/features/ai/shared/search-utils'
import type { LiveEnvironmentDTO } from '@/shared/api/contracts'

export function filterEnvironments(
  environments: LiveEnvironmentDTO[],
  search: string,
): LiveEnvironmentDTO[] {
  return environments
    .filter((environment) => includesSearch(environment.name, search))
    .sort((left, right) => naturalNameCompare(left.name, right.name))
}
