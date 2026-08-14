import type { ModelAttemptFailureDialogueMessage } from '@/features/ai/runtime/thread-timeline-types'

export interface ModelAttemptFailureMessageInput {
  id: string
  subjectEntryId: string | null
  createdAt: ModelAttemptFailureDialogueMessage['createdAt']
  attempt: number
  sequence: string
  text: string
  thinking: string
  errorCode: string
  errorMessage: string
  failedAt: ModelAttemptFailureDialogueMessage['failedAt']
  retryAt: ModelAttemptFailureDialogueMessage['retryAt']
  nextAttempt: number | null
  modelInvocationId?: string
  turnStartEntryId?: string
  basisHeadEntryId?: string
}

export function createModelAttemptFailureMessage(
  input: ModelAttemptFailureMessageInput,
): ModelAttemptFailureDialogueMessage {
  return {
    id: input.id,
    role: 'model_attempt_failure',
    subjectEntryId: input.subjectEntryId,
    createdAt: input.createdAt,
    status: 'done',
    attempt: input.attempt,
    sequence: input.sequence,
    text: input.text,
    thinking: input.thinking,
    errorCode: input.errorCode,
    errorMessage: input.errorMessage,
    failedAt: input.failedAt,
    retryAt: input.retryAt,
    nextAttempt: input.nextAttempt,
    ...(input.modelInvocationId == null
      ? {}
      : { modelInvocationId: input.modelInvocationId }),
    ...(input.turnStartEntryId == null
      ? {}
      : { turnStartEntryId: input.turnStartEntryId }),
    ...(input.basisHeadEntryId == null
      ? {}
      : { basisHeadEntryId: input.basisHeadEntryId }),
  }
}
