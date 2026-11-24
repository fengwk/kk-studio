package fun.fengwk.kkstudio.platform.environment.dto;

import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.List;

/** Narrow environment card data for workspace selection. */
public record EnvironmentSummaryDTO(
    String id,
    String name,
    EnvironmentStatus status,
    boolean ready,
    @Nullable Instant statusExpiresAt,
    List<String> capabilities) {}
