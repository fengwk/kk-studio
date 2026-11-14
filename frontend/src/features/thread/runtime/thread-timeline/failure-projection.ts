import type { DialogueMessage } from '@/features/thread/runtime/thread-timeline-types'
import { createModelAttemptFailureMessage } from '@/features/thread/runtime/thread-timeline/model-attempt-failure'
import type {
  HarnessActiveAssistantOutputDTO,
  HarnessGenerationFailureDTO,
} from '@/shared/api/generated/contracts'
import { isProjectableGenerationFailure } from '@/features/thread/runtime/thread-timeline/model-stream-projection'

export function projectGenerationFailures(
  messages: DialogueMessage[],
  failures: readonly HarnessGenerationFailureDTO[],
  activeAssistantOutput: HarnessActiveAssistantOutputDTO | null,
): void {
  const identities = new Set<string>()
  const orderedFailures = [...failures].sort((left, right) => left.attempt - right.attempt)
  for (const failure of orderedFailures) {
    if (!isProjectableGenerationFailure(failure)) {
      continue
    }
    const identity = `${failure.generationId}:${failure.attempt}`
    if (identities.has(identity)) {
      continue
    }
    identities.add(identity)
    const liveRetry = isLiveRetryPending(failure, activeAssistantOutput)
    messages.push(
      createModelAttemptFailureMessage({
        id: `snapshot:model-attempt-failure:${identity}`,
        subjectEntryId: null,
        createdAt: failure.failedAt,
        attempt: failure.attempt,
        sequence: failure.sequence,
        text: failure.text,
        thinking: failure.thinking,
        errorCode: failure.errorCode,
        errorMessage: failure.errorMessage,
        failedAt: failure.failedAt,
        retryAt: failure.retryAt,
        nextAttempt: failure.attempt + 1,
        generationId: liveRetry ? failure.generationId : undefined,
        turnStartEntryId: failure.turnStartEntryId,
        requestHeadEntryId: failure.requestHeadEntryId,
      }),
    )
  }
}

function isLiveRetryPending(
  failure: HarnessGenerationFailureDTO,
  activeAssistantOutput: HarnessActiveAssistantOutputDTO | null,
): boolean {
  return activeAssistantOutput != null
    && activeAssistantOutput.generationId === failure.generationId
    && activeAssistantOutput.attempt === failure.attempt
    && activeAssistantOutput.status === 'READY'
}
