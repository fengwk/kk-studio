package fun.fengwk.kkstudio.web.controller;

import fun.fengwk.convention4j.api.result.Result;
import fun.fengwk.convention4j.common.result.Results;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import fun.fengwk.kkstudio.platform.harness.realtime.HarnessStreamCatchupService;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessThreadStreamCatchupDTO;
import fun.fengwk.kkstudio.web.runtime.HarnessRuntimeRequestMapper;

import java.util.Objects;

/**
 * 流式追帧请求入口：客户端把已观察到的 invocation/attempt 交给服务端，由当前 active owner 立即提交 checkpoint。
 *
 * <p>成功固定返回 202：请求只保证已按 durable 快照与 attempt fence 接受，不承诺已经落盘或仍有 active owner。 Thread 不存在翻译为
 * 404，请求形状（canonical UUID、正整数 attempt、未知/重复字段等）翻译为 400。
 */
@RestController
@RequestMapping("/api/threads")
public class StudioHarnessThreadStreamCatchupController {

  private final HarnessStreamCatchupService catchupService;

  public StudioHarnessThreadStreamCatchupController(HarnessStreamCatchupService catchupService) {
    this.catchupService = Objects.requireNonNull(catchupService, "catchupService");
  }

  @PostMapping("/{threadId}/stream-catchup")
  public Result<Void> catchUp(
      @PathVariable String threadId, @RequestBody HarnessThreadStreamCatchupDTO request) {
    return Results.accepted(
        StudioHarnessThreadController.withRuntimeTranslation(
            () -> {
              if (request == null) {
                throw new IllegalArgumentException("stream catchup request body is required");
              }
              HarnessRuntimeRequestMapper.StreamCatchupRequest parsed =
                  HarnessRuntimeRequestMapper.toStreamCatchupRequest(threadId, request);
              catchupService.catchUp(parsed.threadId(), parsed.invocationId(), parsed.attempt());
              return null;
            }));
  }
}
