package fun.fengwk.kkstudio.web.controller;

import fun.fengwk.convention4j.api.result.Result;
import fun.fengwk.convention4j.common.result.Results;
import lombok.AllArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import fun.fengwk.kkstudio.platform.comfyui.ComfyuiFileDownload;
import fun.fengwk.kkstudio.platform.comfyui.ComfyuiRuntimeService;
import fun.fengwk.kkstudio.share.comfyui.ComfyuiWorkflowCancelDTO;
import fun.fengwk.kkstudio.share.comfyui.ComfyuiWorkflowJobDTO;
import fun.fengwk.kkstudio.share.comfyui.ComfyuiWorkflowRunDTO;
import fun.fengwk.kkstudio.share.comfyui.ComfyuiWorkflowRunRequestDTO;
import fun.fengwk.kkstudio.web.mapper.WebDtoMapper;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * ComfyUI 无状态任务 API。
 *
 * <p>控制器总是注册的；{@link ComfyuiRuntimeService} 也是总是可注入的 bean。当启动快照中的 {@code
 * integrations.comfyui.enabled=false} 或没有 {@code ComfyUIClient} 时，运行期方法会抛 {@link
 * IllegalStateException}，本控制器将其翻译为 HTTP 503，明确告诉调用方服务未启用，而非 404。
 *
 * <p>{@code runId} 直接等于 ComfyUI prompt / job id，不在后端持久化。 输出下载只能按当前 job outputs 中的 node/media/index
 * 精确解析。
 *
 * @author fengwk
 */
@AllArgsConstructor
@RequestMapping("/api/comfyui")
@RestController
public class StudioComfyuiRuntimeController {

  private static final String DISABLED_RUNTIME_PREFIX = "ComfyUI runtime is disabled";
  private static final String UNAVAILABLE_CLIENT_PREFIX = "ComfyUI client is unavailable";

  private final ComfyuiRuntimeService comfyuiRuntimeService;

  @PostMapping("/workflows/{workflowId}/runs")
  public Result<ComfyuiWorkflowRunDTO> run(
      @PathVariable("workflowId") String workflowIdText,
      @RequestBody(required = false) ComfyuiWorkflowRunRequestDTO request) {
    UUID workflowId = parseUuid(workflowIdText, "workflowId");
    try {
      return Results.accepted(comfyuiRuntimeService.run(workflowId, request));
    } catch (IllegalArgumentException error) {
      throw translateNotFound(error);
    }
  }

  @GetMapping("/runs/{runId}")
  public Result<ComfyuiWorkflowJobDTO> getJob(
      @PathVariable("runId") String runId,
      @RequestParam(value = "select", required = false) String select) {
    try {
      return Results.ok(comfyuiRuntimeService.getJob(runId, select));
    } catch (IllegalArgumentException error) {
      throw translateNotFound(error);
    }
  }

  @PostMapping("/runs/{runId}/cancel")
  public Result<ComfyuiWorkflowCancelDTO> cancel(@PathVariable("runId") String runId) {
    try {
      return Results.ok(comfyuiRuntimeService.cancel(runId));
    } catch (IllegalArgumentException error) {
      throw translateNotFound(error);
    }
  }

  @GetMapping("/runs/{runId}/files/{nodeId}/{mediaType}/{index}")
  public ResponseEntity<byte[]> downloadFile(
      @PathVariable("runId") String runId,
      @PathVariable("nodeId") String nodeId,
      @PathVariable("mediaType") String mediaType,
      @PathVariable("index") int index) {
    ComfyuiFileDownload download =
        comfyuiRuntimeService.downloadFile(runId, nodeId, mediaType, index);
    ContentDisposition disposition =
        ContentDisposition.attachment()
            .filename(download.getFilename(), StandardCharsets.UTF_8)
            .build();
    return ResponseEntity.ok()
        .contentType(MediaType.parseMediaType(download.getContentType()))
        .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
        .body(download.getBytes());
  }

  /**
   * 明确把"运行期未启用 / 客户端不可用"的 {@link IllegalStateException} 翻译为 503，避免与 404 混淆。其它 {@link
   * IllegalStateException} 保持原样交给全局 handler。
   */
  @ExceptionHandler(IllegalStateException.class)
  public ResponseStatusException handleRuntimeUnavailable(IllegalStateException error) {
    String message = error.getMessage();
    if (message != null
        && (message.startsWith(DISABLED_RUNTIME_PREFIX)
            || message.startsWith(UNAVAILABLE_CLIENT_PREFIX))) {
      return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, message, error);
    }
    throw error;
  }

  /**
   * 把"运行期提交路径上 workflowId 找不到已启用卡片"的 {@link IllegalArgumentException} 翻译为 404，其它 {@link
   * IllegalArgumentException}（参数 / 选择器 / binding 校验）保持 400。
   */
  private static RuntimeException translateNotFound(IllegalArgumentException error) {
    String message = error.getMessage();
    if (message != null && message.startsWith("enabled ComfyUI workflow not found:")) {
      return new ResponseStatusException(HttpStatus.NOT_FOUND, message, error);
    }
    return error;
  }

  private static UUID parseUuid(String text, String name) {
    try {
      return WebDtoMapper.parseUuid(text, name);
    } catch (IllegalArgumentException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage(), e);
    }
  }
}
