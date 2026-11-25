package fun.fengwk.kkstudio.workspace.dto;

import java.util.UUID;

public record SessionCreatedDTO(
    UUID sessionId, UUID threadId, UUID commandId, String treeVersion) {}
