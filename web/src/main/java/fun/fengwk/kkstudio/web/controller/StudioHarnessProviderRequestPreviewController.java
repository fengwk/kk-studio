package fun.fengwk.kkstudio.web.controller;

import fun.fengwk.convention4j.api.result.Result;
import fun.fengwk.convention4j.common.result.Results;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import fun.fengwk.kkstudio.harness.runtime.AcceptCommandsCommand;
import fun.fengwk.kkstudio.platform.harness.model.ProviderRequestPreviewService;
import fun.fengwk.kkstudio.platform.harness.model.ProviderRequestPreviewUnavailableException;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessProviderRequestPreviewDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessThreadCommandBatchDTO;
import fun.fengwk.kkstudio.web.runtime.HarnessRuntimeRequestMapper;

import java.util.Objects;
import java.util.UUID;

/**
 * 发送前请求预览入口：请求体只携带目标 Thread 的 cursor 与有序命令，返回「现在发送会发出的」Provider 协议请求体。
 *
 * <p>请求形状由 {@link HarnessRuntimeRequestMapper} 严格校验（非 canonical UUID / 非法 batch -&gt; 400）；缺失
 * Thread 由 Runtime 的 typed 异常翻译为 404；当前事实不允许精确预览（游标漂移、非空闲、queued、 下一步压缩、附件未就绪、adapter 不支持预览）统一为
 * 409。附件引用仍必须由目标 Session 持有，预览不写任何 durable 状态、不消费 upload、不触发 transport，因此响应不包含 HTTP 状态 202
 * 之类的「已接受」语义。预览不再要求客户端伪造产品 owner。
 */
@RestController
@RequestMapping("/api/harness/threads")
public class StudioHarnessProviderRequestPreviewController {

  private final ProviderRequestPreviewService previewService;

  public StudioHarnessProviderRequestPreviewController(
      ProviderRequestPreviewService previewService) {
    this.previewService = Objects.requireNonNull(previewService, "previewService");
  }

  /** 现算一次草稿的最终 Provider 请求体；响应 200 只表示该快照下请求可以被精确预览。 */
  @PostMapping("/{threadId}/provider-request-preview")
  public Result<HarnessProviderRequestPreviewDTO> preview(
      @PathVariable String threadId, @RequestBody HarnessThreadCommandBatchDTO request) {
    try {
      return Results.ok(
          StudioHarnessThreadController.withRuntimeTranslation(
              () -> {
                UUID id = HarnessRuntimeRequestMapper.parseUuid(threadId, "threadId");
                AcceptCommandsCommand command =
                    HarnessRuntimeRequestMapper.toAcceptThreadCommandsCommand(threadId, request);
                return previewService.preview(id, command);
              }));
    } catch (ProviderRequestPreviewUnavailableException error) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, error.getMessage(), error);
    }
  }
}
