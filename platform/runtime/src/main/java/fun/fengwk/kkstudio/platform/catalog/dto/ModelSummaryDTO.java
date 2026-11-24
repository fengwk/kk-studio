package fun.fengwk.kkstudio.platform.catalog.dto;

/** Narrow model selection data for workspace execution. */
import org.jspecify.annotations.Nullable;

import java.util.List;

public record ModelSummaryDTO(
    String name, String type, @Nullable String defaultVariant, List<String> variantIds) {}
