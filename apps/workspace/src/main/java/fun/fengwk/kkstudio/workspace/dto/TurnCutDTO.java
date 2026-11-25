package fun.fengwk.kkstudio.workspace.dto;

import java.util.UUID;

public record TurnCutDTO(UUID sourceThreadId, UUID turnEndEntryId, String title) {}
