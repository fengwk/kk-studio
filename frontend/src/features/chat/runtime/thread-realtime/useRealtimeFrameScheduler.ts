import { useEffect, useState } from 'react'
import {
  createRealtimeFrameScheduler,
  type RealtimeFrameScheduler,
  type RealtimeFrameSchedulerOptions,
} from '@/features/chat/runtime/realtime-frame-scheduler'
import {
  isFailedGeneration,
  isTerminalAssistantOutputStatus,
  type RealtimeModelStream,
} from '@/features/chat/runtime/thread-realtime/model-stream'
import {
  type RealtimeToolStream,
} from '@/features/chat/runtime/thread-realtime/tool-stream'
import type {
  HarnessActiveAssistantOutputDTO,
  HarnessGenerationFailureDTO,
} from '@/shared/api/generated/contracts'

export function useRealtimeFrameScheduler(
  modelStreamRef: { readonly current: RealtimeModelStream | null },
  activeOutputRef: { readonly current: HarnessActiveAssistantOutputDTO | null },
  generationFailuresRef: { readonly current: readonly HarnessGenerationFailureDTO[] },
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
        const durable = activeOutputRef.current
        const isTerminal =
          durable == null
          || durable.resultEntryId != null
          || isTerminalAssistantOutputStatus(durable.status)
        const isFailed =
          current != null
          && durable != null
          && current.invocationId === durable.generationId
          && isFailedGeneration(durable.generationId, current.attempt, generationFailuresRef.current)
        if (!isTerminal && !isFailed) {
          setModelStream(current)
        }
      }
      if (toolDirty) {
        setToolStreams(new Map(toolStreamsRef.current))
      }
    })
  }, [
    activeOutputRef,
    generationFailuresRef,
    modelStreamRef,
    scheduler,
    setModelStream,
    setToolStreams,
    toolStreamsRef,
  ])

  return scheduler
}
