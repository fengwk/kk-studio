package fun.fengwk.kkstudio.platform.harness.http;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import fun.fengwk.kkstudio.platform.harness.builtin.realtime.HarnessStreamCatchupService;
import fun.fengwk.kkstudio.platform.harness.dto.HarnessThreadStreamCatchupDTO;

import java.util.Objects;
import java.util.UUID;

/**
 * 流式追帧请求入口：客户端把已观察到的 invocation/attempt 交给服务端，由当前 active owner 立即提交 checkpoint。
 *
 * <p>成功固定返回 202：请求只保证已按 durable 快照与 attempt fence 接受，不承诺已经落盘或仍有 active owner。 Thread 不存在翻译为
 * 404，请求形状（canonical UUID、正整数 attempt、未知/重复字段等）翻译为 400。
 */
@RestController
@RequestMapping("/api/harness/builtin/threads")
public class HarnessThreadStreamCatchupController {

  private final HarnessStreamCatchupService catchupService;

  public HarnessThreadStreamCatchupController(HarnessStreamCatchupService catchupService) {
    this.catchupService = Objects.requireNonNull(catchupService, "catchupService");
  }

  @PostMapping("/{threadId}/stream-catchup")
  @ResponseStatus(HttpStatus.ACCEPTED)
  public void catchUp(
      @PathVariable UUID threadId, @RequestBody HarnessThreadStreamCatchupDTO request) {
    catchupService.catchUp(threadId, request.getInvocationId(), request.getAttempt());
  }
}
