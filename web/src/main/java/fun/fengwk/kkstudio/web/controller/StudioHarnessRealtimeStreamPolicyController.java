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

import fun.fengwk.kkstudio.core.ai.runtime.realtime.stream.HarnessRealtimeStreamPolicyService;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessRealtimeStreamPolicyDTO;

/** 全局 Harness realtime Stream 保留策略 API。 */
@AllArgsConstructor
@RestController
@RequestMapping("/api/harness/realtime-stream-policy")
public class StudioHarnessRealtimeStreamPolicyController {
  private final HarnessRealtimeStreamPolicyService realtimeStreamPolicyService;

  @GetMapping
  public Result<HarnessRealtimeStreamPolicyDTO> getPolicy() {
    return Results.ok(realtimeStreamPolicyService.getPolicy());
  }

  @PutMapping
  public Result<HarnessRealtimeStreamPolicyDTO> updatePolicy(
      @RequestBody HarnessRealtimeStreamPolicyDTO realtimeStreamPolicy) {
    try {
      return Results.ok(realtimeStreamPolicyService.updatePolicy(realtimeStreamPolicy));
    } catch (IllegalArgumentException error) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, error.getMessage(), error);
    }
  }
}
