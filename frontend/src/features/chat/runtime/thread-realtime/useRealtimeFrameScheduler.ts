import { useEffect, useState } from 'react'
import {
  createRealtimeFrameScheduler,
  type RealtimeFrameScheduler,
  type RealtimeFrameSchedulerOptions,
} from '@/features/chat/runtime/realtime-frame-scheduler'
import {
  isFailedAttempt,
  type RealtimeModelStream,
} from '@/features/chat/runtime/thread-realtime/model-stream'
import {
  type RealtimeToolStream,
} from '@/features/chat/runtime/thread-realtime/tool-stream'
import type { ModelAttemptFailureDTO, ModelInvocationDTO } from '@/shared/api/contracts/ai-runtime'

export function useRealtimeFrameScheduler(
  modelStreamRef: { readonly current: RealtimeModelStream | null },
  invocationRef: { readonly current: ModelInvocationDTO | null },
  modelAttemptFailuresRef: { readonly current: readonly ModelAttemptFailureDTO[] },
  toolStreamsRef: { readonly current: Map<string, RealtimeToolStream> },
  setModelStream: (stream: RealtimeModelStream | null) => void,
  setToolStreams: (streams: ReadonlyMap<string, RealtimeToolStream>) => void,
  schedulerOptions?: Partial<RealtimeFrameSchedulerOptions>,
): RealtimeFrameScheduler {
  const [scheduler] = useState(() =>
    createRealtimeFrameScheduler(schedulerOptions),
  )

  useEffect(() => {
    scheduler.setOnFlush(({ modelDirty, toolDirty }) => {
      if (modelDirty) {
        const current = modelStreamRef.current
        const durable = invocationRef.current
        const isTerminal =
          durable != null
          && (durable.resultJson != null || durable.errorJson != null || durable.resultEntryId != null)
        const isFailed =
          current != null
          && durable != null
          && isFailedAttempt(current.attempt, durable, modelAttemptFailuresRef.current)
        if (!isTerminal && !isFailed) {
          setModelStream(current)
        }
      }
      if (toolDirty) {
        setToolStreams(new Map(toolStreamsRef.current))
      }
    })
  }, [
    invocationRef,
    modelAttemptFailuresRef,
    modelStreamRef,
    scheduler,
    setModelStream,
    setToolStreams,
    toolStreamsRef,
  ])

  return scheduler
}
