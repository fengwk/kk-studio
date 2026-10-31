package fun.fengwk.kkstudio.web.controller;

import lombok.AllArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import fun.fengwk.kkstudio.ide.EnvironmentIdeService;
import fun.fengwk.kkstudio.share.ai.environment.EnvironmentIdeChangeDirectoryDTO;
import fun.fengwk.kkstudio.share.ai.environment.EnvironmentIdeCloseViewDTO;
import fun.fengwk.kkstudio.share.ai.environment.EnvironmentIdeOpenViewDTO;
import fun.fengwk.kkstudio.share.ai.environment.EnvironmentIdeViewBindingDTO;
import fun.fengwk.kkstudio.share.ai.environment.EnvironmentIdeViewDTO;
import fun.fengwk.kkstudio.web.api.ApiResponse;
import fun.fengwk.kkstudio.web.api.RequestParameters;

/**
 * Environment IDE 视图控制 API：把 Studio pane 的 view 生命周期翻译为对 Daemon 的 IDE 命令。
 *
 * <p>{@code OPEN_VIEW} 返回受控的同源 entryUrl；{@code CHANGE_DIRECTORY} 明确拒绝 dirty（由 Daemon 判定）； {@code
 * FOCUS_TERMINAL} 只新建/显示人工终端，不模拟输入；{@code CLOSE_VIEW} 幂等清理该 view，不影响其它 view 或 Agent。
 */
@AllArgsConstructor
@RequestMapping("/api/environments/{environmentId}/ide/views")
@RestController
public class StudioEnvironmentIdeController {

  private final EnvironmentIdeService environmentIdeService;

  @PostMapping
  public ApiResponse<EnvironmentIdeViewDTO> openView(
      @PathVariable String environmentId, @RequestBody EnvironmentIdeOpenViewDTO request) {
    return ApiResponse.ok(
        environmentIdeService.openView(RequestParameters.environmentId(environmentId), request));
  }

  @PutMapping("/{viewId}/directory")
  public ApiResponse<EnvironmentIdeViewDTO> changeDirectory(
      @PathVariable String environmentId,
      @PathVariable String viewId,
      @RequestBody EnvironmentIdeChangeDirectoryDTO request) {
    return ApiResponse.ok(
        environmentIdeService.changeDirectory(
            RequestParameters.environmentId(environmentId), viewId, request));
  }

  @PostMapping("/{viewId}/terminal")
  public ApiResponse<Void> focusTerminal(
      @PathVariable String environmentId,
      @PathVariable String viewId,
      @RequestBody EnvironmentIdeViewBindingDTO request) {
    environmentIdeService.focusTerminal(
        RequestParameters.environmentId(environmentId), viewId, request);
    return ApiResponse.noContent();
  }

  @DeleteMapping("/{viewId}")
  public ApiResponse<Void> closeView(
      @PathVariable String environmentId,
      @PathVariable String viewId,
      @RequestBody EnvironmentIdeCloseViewDTO request) {
    environmentIdeService.closeView(
        RequestParameters.environmentId(environmentId), viewId, request);
    return ApiResponse.noContent();
  }
}
