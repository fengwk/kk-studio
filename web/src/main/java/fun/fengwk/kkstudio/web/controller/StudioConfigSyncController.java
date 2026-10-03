package fun.fengwk.kkstudio.web.controller;

import fun.fengwk.convention4j.api.result.Result;
import fun.fengwk.convention4j.common.result.Results;
import lombok.AllArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import fun.fengwk.kkstudio.platform.configsync.ConfigSyncService;
import fun.fengwk.kkstudio.share.configsync.ConfigSyncExportDTO;
import fun.fengwk.kkstudio.share.configsync.ConfigSyncExportRequestDTO;
import fun.fengwk.kkstudio.share.configsync.ConfigSyncImportRequestDTO;
import fun.fengwk.kkstudio.share.configsync.ConfigSyncImportResultDTO;
import fun.fengwk.kkstudio.share.configsync.ConfigSyncInventoryDTO;

/**
 * 设置同步 HTTP 边界：inventory、导出与导入。
 *
 * <p>请求与 YAML 可能内嵌凭据，因此三个端点全部返回 {@code Cache-Control: no-store}，且请求体绝不进入日志。
 */
@AllArgsConstructor
@RequestMapping("/api/settings/sync")
@RestController
public class StudioConfigSyncController {

  private static final String NO_STORE = "no-store";

  private final ConfigSyncService configSyncService;

  @GetMapping
  public ResponseEntity<Result<ConfigSyncInventoryDTO>> inventory() {
    return noStore(Results.ok(configSyncService.inventory()));
  }

  @PostMapping("/export")
  public ResponseEntity<Result<ConfigSyncExportDTO>> export(
      @RequestBody ConfigSyncExportRequestDTO request) {
    return noStore(Results.ok(configSyncService.export(request)));
  }

  @PostMapping("/import")
  public ResponseEntity<Result<ConfigSyncImportResultDTO>> importYaml(
      @RequestBody ConfigSyncImportRequestDTO request) {
    return noStore(Results.ok(configSyncService.importYaml(request)));
  }

  private static <T> ResponseEntity<Result<T>> noStore(Result<T> body) {
    return ResponseEntity.status(body.getStatus())
        .header(HttpHeaders.CACHE_CONTROL, NO_STORE)
        .body(body);
  }
}
