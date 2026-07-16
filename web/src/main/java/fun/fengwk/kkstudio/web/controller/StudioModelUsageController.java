package fun.fengwk.kkstudio.web.controller;

import fun.fengwk.convention4j.api.result.Result;
import fun.fengwk.convention4j.common.result.Results;
import fun.fengwk.kkstudio.core.harness.usage.service.ModelUsageAggregationService;
import fun.fengwk.kkstudio.share.model.ModelUsageSummaryDTO;
import lombok.AllArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 模型调用账本的 Run、Session 与 Model 聚合 API。 */
@AllArgsConstructor
@RequestMapping("/api/usage")
@RestController
public class StudioModelUsageController {

  private final ModelUsageAggregationService aggregationService;

  @GetMapping("/runs/{runId}")
  public Result<ModelUsageSummaryDTO> summarizeRun(@PathVariable("runId") String runId) {
    return Results.ok(aggregationService.summarizeRun(parsePositiveLong(runId, "runId")));
  }

  @GetMapping("/sessions/{sessionId}")
  public Result<ModelUsageSummaryDTO> summarizeSession(
      @PathVariable("sessionId") String sessionId) {
    return Results.ok(
        aggregationService.summarizeSession(parsePositiveLong(sessionId, "sessionId")));
  }

  @GetMapping("/models/{modelId}")
  public Result<ModelUsageSummaryDTO> summarizeModel(@PathVariable("modelId") String modelId) {
    return Results.ok(aggregationService.summarizeModel(parsePositiveLong(modelId, "modelId")));
  }

  private static long parsePositiveLong(String raw, String name) {
    long parsed;
    try {
      parsed = Long.parseLong(raw);
    } catch (NumberFormatException error) {
      throw new IllegalArgumentException(name + " must be a positive long: " + raw, error);
    }
    if (parsed <= 0L) {
      throw new IllegalArgumentException(name + " must be a positive long: " + raw);
    }
    return parsed;
  }
}
