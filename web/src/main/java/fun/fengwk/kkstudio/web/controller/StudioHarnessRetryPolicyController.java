package fun.fengwk.kkstudio.web.controller;

import fun.fengwk.convention4j.api.result.Result;
import fun.fengwk.convention4j.common.result.Results;
import lombok.AllArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import fun.fengwk.kkstudio.core.harness.retry.HarnessRetryPolicyService;
import fun.fengwk.kkstudio.share.model.HarnessRetryPolicyDTO;

/** 全局 Harness 自动重试策略 API。 */
@AllArgsConstructor
@RestController
@RequestMapping("/api/harness/retry-policy")
public class StudioHarnessRetryPolicyController {
  private final HarnessRetryPolicyService retryPolicyService;

  @GetMapping
  public Result<HarnessRetryPolicyDTO> getRetryPolicy() {
    return Results.ok(retryPolicyService.getRetryPolicy());
  }

  @PutMapping
  public Result<HarnessRetryPolicyDTO> updateRetryPolicy(
      @RequestBody HarnessRetryPolicyDTO retryPolicy) {
    try {
      return Results.ok(retryPolicyService.updateRetryPolicy(retryPolicy));
    } catch (IllegalArgumentException error) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, error.getMessage(), error);
    }
  }
}
