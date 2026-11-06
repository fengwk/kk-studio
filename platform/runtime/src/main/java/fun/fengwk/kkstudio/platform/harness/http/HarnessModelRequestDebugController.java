package fun.fengwk.kkstudio.platform.harness.http;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import fun.fengwk.kkstudio.platform.harness.builtin.query.ModelRequestDebugService;
import fun.fengwk.kkstudio.platform.harness.dto.HarnessModelRequestDebugDTO;
import fun.fengwk.kkstudio.platform.harness.dto.HarnessModelRequestDebugRequestDTO;

import java.util.Objects;
import java.util.UUID;

/**
 * 按 UI 当前草稿 model / environment 现算结构化 Model Request Debug 投影（read-only）：与正式 Turn 共用同一 planner， 不检查
 * branch HEAD、不发布 Package、不触发 sync、不产生写入，也不改动任何持久事实。
 */
@RestController
@RequestMapping("/api/harness/builtin/threads")
public class HarnessModelRequestDebugController {

  private final ModelRequestDebugService modelRequestDebugService;

  public HarnessModelRequestDebugController(ModelRequestDebugService modelRequestDebugService) {
    this.modelRequestDebugService =
        Objects.requireNonNull(modelRequestDebugService, "modelRequestDebugService");
  }

  @PostMapping("/{threadId}/model-request-debug")
  public HarnessModelRequestDebugDTO previewModelRequest(
      @PathVariable UUID threadId, @RequestBody HarnessModelRequestDebugRequestDTO request) {
    HarnessRuntimeRequestMapper.ModelRequestDebugSelection selection =
        HarnessRequestErrors.map(
            () -> HarnessRuntimeRequestMapper.toModelRequestDebugSelection(request));
    return modelRequestDebugService.getModelRequestDebug(
        threadId, selection.model(), selection.environmentName());
  }
}
