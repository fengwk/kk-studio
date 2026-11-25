package fun.fengwk.kkstudio.workspace.dto;

import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.UUID;

public record WorkspaceThreadDTO(
    UUID id,
    String title,
    WorkspaceThreadState state,
    @Nullable WorkspaceThreadStateReason reason,
    String version,
    Instant lastActivityAt) {}
