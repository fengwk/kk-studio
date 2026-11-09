package fun.fengwk.kkstudio.ide.http;

import lombok.AllArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.ide.EnvironmentIdeService;
import fun.fengwk.kkstudio.ide.dto.EnvironmentIdeChangeDirectoryDTO;
import fun.fengwk.kkstudio.ide.dto.EnvironmentIdeCloseViewDTO;
import fun.fengwk.kkstudio.ide.dto.EnvironmentIdeDirectoryListingDTO;
import fun.fengwk.kkstudio.ide.dto.EnvironmentIdeOpenViewDTO;
import fun.fengwk.kkstudio.ide.dto.EnvironmentIdeReadViewDTO;
import fun.fengwk.kkstudio.ide.dto.EnvironmentIdeViewBindingDTO;
import fun.fengwk.kkstudio.ide.dto.EnvironmentIdeViewDTO;
import fun.fengwk.kkstudio.ide.dto.EnvironmentIdeViewSnapshotDTO;

import java.util.UUID;

/**
 * Environment IDE 视图控制 API：把 Studio pane 的 view 生命周期翻译为对 Daemon 的 IDE 命令。
 *
 * <p>{@code OPEN_VIEW} 返回受控的同源 entryUrl；{@code CHANGE_DIRECTORY} 明确拒绝 dirty（由 Daemon 判定）； {@code
 * FOCUS_TERMINAL} 只新建/显示人工终端，不模拟输入；{@code CLOSE_VIEW} 幂等清理该 view，不影响其它 view 或 Agent。
 */
@AllArgsConstructor
@RequestMapping("/api/ide/environments/{environmentId}")
@RestController
public class EnvironmentIdeController {

  private final EnvironmentIdeService environmentIdeService;

  @PostMapping("/views")
  public EnvironmentIdeViewDTO openView(
      @PathVariable UUID environmentId, @RequestBody EnvironmentIdeOpenViewDTO request) {
    return environmentIdeService.openView(EnvironmentId.of(environmentId), request);
  }

  @GetMapping("/views/{viewId}")
  public EnvironmentIdeViewSnapshotDTO readView(
      @PathVariable UUID environmentId,
      @PathVariable UUID viewId,
      @RequestParam("browserSessionId") UUID browserSessionId,
      @RequestParam("eventNodeId") UUID eventNodeId,
      @RequestParam("connectionId") UUID connectionId,
      @RequestParam("claimId") UUID claimId,
      @RequestParam(value = "expectedDaemonInstanceId", required = false)
          String expectedDaemonInstanceId) {
    EnvironmentIdeReadViewDTO request = new EnvironmentIdeReadViewDTO();
    request.setBrowserSessionId(browserSessionId.toString());
    request.setEventNodeId(eventNodeId.toString());
    request.setConnectionId(connectionId.toString());
    request.setClaimId(claimId.toString());
    request.setExpectedDaemonInstanceId(expectedDaemonInstanceId);
    return environmentIdeService.readView(
        EnvironmentId.of(environmentId), viewId.toString(), request);
  }

  @PutMapping("/views/{viewId}/directory")
  public EnvironmentIdeViewDTO changeDirectory(
      @PathVariable UUID environmentId,
      @PathVariable UUID viewId,
      @RequestBody EnvironmentIdeChangeDirectoryDTO request) {
    return environmentIdeService.changeDirectory(
        EnvironmentId.of(environmentId), viewId.toString(), request);
  }

  @PostMapping("/views/{viewId}/focus-terminal")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void focusTerminal(
      @PathVariable UUID environmentId,
      @PathVariable UUID viewId,
      @RequestBody EnvironmentIdeViewBindingDTO request) {
    environmentIdeService.focusTerminal(
        EnvironmentId.of(environmentId), viewId.toString(), request);
  }

  @GetMapping("/directories")
  public EnvironmentIdeDirectoryListingDTO listDirectories(
      @PathVariable UUID environmentId, @RequestParam(required = false) String path) {
    return environmentIdeService.listDirectories(EnvironmentId.of(environmentId), path);
  }

  @PostMapping("/views/{viewId}/apply-mobile-layout")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void collapseMobile(
      @PathVariable UUID environmentId,
      @PathVariable UUID viewId,
      @RequestBody EnvironmentIdeViewBindingDTO request) {
    environmentIdeService.collapseMobile(
        EnvironmentId.of(environmentId), viewId.toString(), request);
  }

  @PostMapping("/views/{viewId}/close")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void closeView(
      @PathVariable UUID environmentId,
      @PathVariable UUID viewId,
      @RequestBody EnvironmentIdeCloseViewDTO request) {
    environmentIdeService.closeView(EnvironmentId.of(environmentId), viewId.toString(), request);
  }
}
