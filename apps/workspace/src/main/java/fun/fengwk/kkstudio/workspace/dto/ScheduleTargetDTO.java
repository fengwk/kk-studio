package fun.fengwk.kkstudio.workspace.dto;

import org.jspecify.annotations.Nullable;

import java.util.UUID;

public record ScheduleTargetDTO(
    Type type,
    @Nullable UUID folderId,
    @Nullable String agentName,
    @Nullable UUID sessionId,
    @Nullable UUID sourceThreadId,
    @Nullable UUID turnEndEntryId,
    @Nullable UUID threadId) {
  public enum Type {
    NEW_SESSION,
    BRANCH,
    CONTINUE
  }
}
