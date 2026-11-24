package fun.fengwk.kkstudio.platform.catalog.dto;

import org.jspecify.annotations.Nullable;

/** Narrow agent selection data for workspace execution. */
public record AgentSummaryDTO(
    String name,
    @Nullable String description,
    @Nullable String driver,
    @Nullable String modelName,
    @Nullable String variant) {}
