package fun.fengwk.kkstudio.platform.harness.http;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import fun.fengwk.kkstudio.platform.harness.builtin.query.ProviderRequestPreviewService;
import fun.fengwk.kkstudio.platform.harness.dto.HarnessProviderRequestPreviewDTO;

import java.util.Objects;
import java.util.UUID;

/**
 * 历史模型输出的 Provider 协议请求重建入口。
 *
 * <p>按当前 catalog / Provider 定义重建，不是原始发送字节；响应以历史 notice 明确声明这一点。缺失 Session 由 Runtime 的 typed 异常翻译为
 * 404，当前事实不允许精确重建统一为 409。
 */
@RestController
@RequestMapping("/api/harness/builtin/sessions")
public class HarnessProviderRequestController {

  private final ProviderRequestPreviewService previewService;

  public HarnessProviderRequestController(ProviderRequestPreviewService previewService) {
    this.previewService = Objects.requireNonNull(previewService, "previewService");
  }

  @GetMapping("/{sessionId}/entries/{entryId}/provider-request")
  public HarnessProviderRequestPreviewDTO getProviderRequest(
      @PathVariable UUID sessionId, @PathVariable UUID entryId) {
    return previewService.previewHistorical(sessionId, entryId);
  }
}
