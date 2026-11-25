package fun.fengwk.kkstudio.workspace.dto;

import java.util.UUID;

public record ForkCreatedDTO(UUID sessionId, UUID threadId, String treeVersion) {}
