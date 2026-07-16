package fun.fengwk.kkstudio.web.controller;

import fun.fengwk.convention4j.api.result.Result;
import fun.fengwk.convention4j.common.result.Results;
import fun.fengwk.kkstudio.core.comfyui.ComfyuiFileDownload;
import fun.fengwk.kkstudio.core.comfyui.ComfyuiRuntimeService;
import fun.fengwk.kkstudio.share.model.ComfyuiWorkflowCancelDTO;
import fun.fengwk.kkstudio.share.model.ComfyuiWorkflowJobDTO;
import fun.fengwk.kkstudio.share.model.ComfyuiWorkflowRunDTO;
import fun.fengwk.kkstudio.share.model.ComfyuiWorkflowRunRequestDTO;
import java.nio.charset.StandardCharsets;
import lombok.AllArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * ComfyUI 无状态任务 API。
 *
 * <p>{@code runId} 直接等于 ComfyUI prompt / job id，不在后端持久化。输出下载只能按当前 job outputs 中的 node/media/index
 * 精确解析。
 *
 * @author fengwk
 */
@AllArgsConstructor
@ConditionalOnProperty(prefix = "kk-studio.comfyui", name = "enabled", havingValue = "true")
@RequestMapping("/api/comfyui")
@RestController
public class StudioComfyuiRuntimeController {

  private final ComfyuiRuntimeService comfyuiRuntimeService;

  @PostMapping("/workflows/{apiName}/runs")
  public Result<ComfyuiWorkflowRunDTO> run(
      @PathVariable("apiName") String apiName,
      @RequestBody(required = false) ComfyuiWorkflowRunRequestDTO request) {
    return Results.created(comfyuiRuntimeService.run(apiName, request));
  }

  @GetMapping("/runs/{runId}")
  public Result<ComfyuiWorkflowJobDTO> getJob(
      @PathVariable("runId") String runId,
      @RequestParam(value = "select", required = false) String select) {
    return Results.ok(comfyuiRuntimeService.getJob(runId, select));
  }

  @PostMapping("/runs/{runId}/cancel")
  public Result<ComfyuiWorkflowCancelDTO> cancel(@PathVariable("runId") String runId) {
    return Results.ok(comfyuiRuntimeService.cancel(runId));
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
}
