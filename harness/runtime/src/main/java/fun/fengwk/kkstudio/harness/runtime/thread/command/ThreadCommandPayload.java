package fun.fengwk.kkstudio.harness.runtime.thread.command;

/**
 * Sealed family of immutable Thread mailbox command payloads.
 *
 * <p>Each payload carries exactly the fields owned by its command type; no generic settings map is
 * permitted at this boundary.
 */
public sealed interface ThreadCommandPayload
    permits UserMessageCommandPayload,
        CustomMessageCommandPayload,
        SetAgentCommandPayload,
        SetModelCommandPayload,
        SetThinkingLevelCommandPayload,
        SetActiveToolsCommandPayload,
        SetYoloCommandPayload,
        SetEnvironmentCommandPayload {

  /** Returns the command type represented by this payload. */
  ThreadCommandType type();
}
