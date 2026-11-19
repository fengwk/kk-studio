import { useEffect, useRef, useState } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { queryKeys } from '@/shared/lib/query-keys'
import { isThreadActiveStatus } from '@/features/thread/runtime/thread-panel/thread-status-format'
import { useThreadSnapshotSync } from '@/features/thread/runtime/state/thread-snapshot-sync'
import type { HarnessThreadSnapshotDTO } from '@/shared/api/generated/contracts'

/** Keep the previously bound active Thread synchronized while a pane navigates elsewhere. */
export function usePaneBackgroundProjection(boundThreadId: string): void {
  const queryClient = useQueryClient()
  const [backgroundThreadId, setBackgroundThreadId] = useState<string | null>(null)
  const previousBoundThreadId = useRef<string | null>(null)
  const backgroundQuery = useQuery<HarnessThreadSnapshotDTO>({
    queryKey: queryKeys.threads.snapshot(backgroundThreadId ?? ''),
    queryFn: async () => { throw new Error('background snapshot cache observer must not fetch') },
    enabled: false,
  })

  useEffect(() => {
    const previousBoundId = previousBoundThreadId.current
    previousBoundThreadId.current = boundThreadId || null
    if (previousBoundId == null || previousBoundId === boundThreadId) {
      return
    }
    const oldSnapshot = queryClient.getQueryData<HarnessThreadSnapshotDTO>(
      queryKeys.threads.snapshot(previousBoundId),
    )
    setBackgroundThreadId(
      oldSnapshot != null && isThreadActiveStatus(oldSnapshot.thread.status)
        ? previousBoundId
        : null,
    )
  }, [boundThreadId, queryClient])

  const backgroundSnapshot = backgroundQuery.data
  useThreadSnapshotSync(
    backgroundThreadId ?? '',
    backgroundSnapshot != null && isThreadActiveStatus(backgroundSnapshot.thread.status),
  )
}
