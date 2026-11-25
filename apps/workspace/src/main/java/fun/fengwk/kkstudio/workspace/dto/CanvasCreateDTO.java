package fun.fengwk.kkstudio.workspace.dto;

import java.util.UUID;

public record CanvasCreateDTO(UUID folderId, String title, String expectedTreeVersion) {}
