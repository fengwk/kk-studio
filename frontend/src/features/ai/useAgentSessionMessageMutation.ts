import { useMutation, useQueryClient, type QueryClient } from '@tanstack/react-query'
import { harnessService } from '@/shared/api/harness-service'
import type { HarnessSessionDTO, HarnessSessionEntryDTO } from '@/shared/api/contracts'
import { queryKeys } from '@/shared/lib/query-keys'

const LEAF_CONFLICT_MARKER = 'session leaf changed'

/**
 * Submit a user message against the freshest known session leaf.
 * Leaf CAS races are recovered by one forced session refresh + single retry.
 */
export function useAgentSessionMessageMutation(
  sessionId: string,
  onSubmitted: () => void | Promise<void>,
) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: async (content: string): Promise<HarnessSessionEntryDTO> => {
      const leaf = await resolveExpectedLeaf(queryClient, sessionId)
      try {
        return await harnessService.createMessage(sessionId, {
          content,
          expectedLeafEntryId: leaf,
        })
      } catch (error) {
        if (!isLeafConflict(error)) {
          throw error
        }
        const refreshed = await refreshSessionLeaf(queryClient, sessionId)
        if (!refreshed || refreshed === leaf) {
          throw error
        }
        return harnessService.createMessage(sessionId, {
          content,
          expectedLeafEntryId: refreshed,
        })
      }
    },
    onSuccess: async () => {
      await onSubmitted()
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: queryKeys.sessions.detail(sessionId) }),
        queryClient.invalidateQueries({ queryKey: queryKeys.sessions.entries(sessionId) }),
        queryClient.invalidateQueries({ queryKey: queryKeys.sessions.runs(sessionId) }),
        queryClient.invalidateQueries({ queryKey: queryKeys.sessions.list }),
      ])
    },
  })
}

function isLeafConflict(error: unknown): boolean {
  const message = error instanceof Error ? error.message : String(error ?? '')
  return message.includes(LEAF_CONFLICT_MARKER)
}

async function resolveExpectedLeaf(queryClient: QueryClient, sessionId: string): Promise<string> {
  const cached = queryClient.getQueryData<HarnessSessionDTO>(queryKeys.sessions.detail(sessionId))
  if (cached?.leafEntryId) {
    return cached.leafEntryId
  }
  const leaf = await refreshSessionLeaf(queryClient, sessionId)
  if (!leaf) {
    throw new Error('会话尚未准备好接收消息')
  }
  return leaf
}

async function refreshSessionLeaf(queryClient: QueryClient, sessionId: string): Promise<string | null> {
  const session = await queryClient.fetchQuery({
    queryKey: queryKeys.sessions.detail(sessionId),
    queryFn: () => harnessService.getSession(sessionId),
  })
  return session.leafEntryId ?? null
}
