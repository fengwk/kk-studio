package fun.fengwk.kkstudio.workspace.dto;

import org.jspecify.annotations.Nullable;

import fun.fengwk.kkstudio.platform.harness.dto.HarnessModelSelectionDTO;
import fun.fengwk.kkstudio.platform.harness.dto.HarnessUserMessageContentDTO;

import java.util.List;
import java.util.UUID;

public record SessionCreateDTO(
    UUID folderId,
    List<HarnessUserMessageContentDTO> message,
    @Nullable HarnessModelSelectionDTO modelSelection,
    @Nullable UUID environmentId,
    @Nullable Boolean yoloEnabled) {}
