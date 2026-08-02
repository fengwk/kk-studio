import type {
  ModelUsageSummaryDTO,
  ToolInvocationDTO,
} from '@/shared/api/contracts/ai-runtime'

export function useHarnessThreadObservability(
  _threadId: string,
  usage: ModelUsageSummaryDTO | undefined,
  toolInvocations: ToolInvocationDTO[],
) {
  return {
    usage,
    toolInvocations,
    observabilityError: null,
  }
}
