package fun.fengwk.kkstudio.workspace.dto;

import org.jspecify.annotations.Nullable;

import java.util.List;

public record ScheduleRunPageDTO(List<ScheduleRunDTO> items, @Nullable String olderCursor) {}
