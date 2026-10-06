package fun.fengwk.kkstudio.web.controller;

import fun.fengwk.convention4j.api.result.Result;
import fun.fengwk.convention4j.common.result.Results;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import fun.fengwk.kkstudio.harness.runtime.thread.command.NewThreadCommand;
import fun.fengwk.kkstudio.platform.harness.model.ProviderRequestPreviewService;
import fun.fengwk.kkstudio.platform.harness.model.ProviderRequestPreviewUnavailableException;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessDraftPreviewRequestDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessProviderRequestPreviewDTO;
import fun.fengwk.kkstudio.web.runtime.HarnessRuntimeRequestMapper;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Session 作用域的请求预览入口：本地分支草稿（Thread 尚未创建）与历史模型输出重建。
 *
 * <p>它们与既有 Thread 草稿预览共用同一条规划/物化/编码路径，只把「事实边界」从 Thread 换成 Session：
 *
 * <ul>
 *   <li>{@code POST /{sessionId}/provider-request-preview}：body 只携带草稿分支起点与有序命令，因此无需为了预览先创建 Thread；
 *       起点必须是该 Session 的 ROOT 或 TURN_END（与 NEW_THREAD 的合法落点一致），命令形状由 {@link
 *       HarnessRuntimeRequestMapper} 与正式接受同一套规则严格校验。
 *   <li>{@code GET /{sessionId}/entries/{entryId}/provider-request-preview}：只接受该 Session 的模型输出
 *       Entry， 请求前缀严格截断在它的 parent。
 * </ul>
 *
 * <p>请求形状与 id 由 {@link HarnessRuntimeRequestMapper} 严格校验（非 canonical UUID / 非法 batch / 越界起点 -&gt;
 * 400）；缺失 Session 由 Runtime 的 typed 异常翻译为 404；当前事实不允许精确预览（规划拒绝、附件未就绪、adapter 不支持预览、编码 失败）统一为
 * 409。预览不写任何 durable 状态、不消费 upload、不触发 transport，因此响应不含任何「已接受」语义。
 */
@RestController
@RequestMapping("/api/harness/sessions")
public class StudioHarnessSessionProviderRequestPreviewController {

  private final ProviderRequestPreviewService previewService;

  public StudioHarnessSessionProviderRequestPreviewController(
      ProviderRequestPreviewService previewService) {
    this.previewService = Objects.requireNonNull(previewService, "previewService");
  }

  /** 现算一次本地分支草稿的最终 Provider 请求体；草稿未落库，因此不做任何 Thread cursor / busy / 压缩判定。 */
  @PostMapping("/{sessionId}/provider-request-preview")
  public Result<HarnessProviderRequestPreviewDTO> previewDraft(
      @PathVariable String sessionId, @RequestBody HarnessDraftPreviewRequestDTO request) {
    try {
      return Results.ok(
          StudioHarnessSessionController.withRuntimeTranslation(
              () -> {
                UUID id = HarnessRuntimeRequestMapper.parseUuid(sessionId, "sessionId");
                UUID startEntryId =
                    HarnessRuntimeRequestMapper.parseUuid(
                        request.getStartEntryId(), "startEntryId");
                List<NewThreadCommand> commands =
                    HarnessRuntimeRequestMapper.toNewThreadCommands(
                        request.getCommands(), "commands");
                return previewService.previewDraft(id, startEntryId, commands);
              }));
    } catch (ProviderRequestPreviewUnavailableException error) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, error.getMessage(), error);
    }
  }

  /** 重建一次历史模型输出的最终 Provider 请求体；按当前 catalog / Provider 定义重建，不是原始发送字节。 */
  @GetMapping("/{sessionId}/entries/{entryId}/provider-request-preview")
  public Result<HarnessProviderRequestPreviewDTO> previewHistorical(
      @PathVariable String sessionId, @PathVariable String entryId) {
    try {
      return Results.ok(
          StudioHarnessSessionController.withRuntimeTranslation(
              () -> {
                UUID id = HarnessRuntimeRequestMapper.parseUuid(sessionId, "sessionId");
                UUID entry = HarnessRuntimeRequestMapper.parseUuid(entryId, "entryId");
                return previewService.previewHistorical(id, entry);
              }));
    } catch (ProviderRequestPreviewUnavailableException error) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, error.getMessage(), error);
    }
  }
}
