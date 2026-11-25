package fun.fengwk.kkstudio.workspace.dto;

import java.time.Instant;
import java.util.List;

public record SchedulePreviewDTO(List<Instant> next) {}
