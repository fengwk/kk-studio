package fun.fengwk.kkstudio.web.controller;

import lombok.AllArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import fun.fengwk.kkstudio.platform.configsync.ConfigSyncService;
import fun.fengwk.kkstudio.share.configsync.ConfigSyncExportDTO;
import fun.fengwk.kkstudio.share.configsync.ConfigSyncExportRequestDTO;
import fun.fengwk.kkstudio.share.configsync.ConfigSyncImportCheckDTO;
import fun.fengwk.kkstudio.share.configsync.ConfigSyncImportCheckRequestDTO;
import fun.fengwk.kkstudio.share.configsync.ConfigSyncImportRequestDTO;
import fun.fengwk.kkstudio.share.configsync.ConfigSyncImportResultDTO;
import fun.fengwk.kkstudio.share.configsync.ConfigSyncInventoryDTO;
import fun.fengwk.kkstudio.web.api.ApiResponse;

/** 配置同步 HTTP 边界；响应缓存由 ConfigSyncNoStoreFilter 统一禁止。 */
@AllArgsConstructor
@RequestMapping("/api/settings/sync")
@RestController
public class StudioConfigSyncController {

  private final ConfigSyncService configSyncService;

  @GetMapping
  public ApiResponse<ConfigSyncInventoryDTO> inventory() {
    return ApiResponse.ok(configSyncService.inventory());
  }

  @PostMapping("/export")
  public ApiResponse<ConfigSyncExportDTO> export(@RequestBody ConfigSyncExportRequestDTO request) {
    return ApiResponse.ok(configSyncService.export(request));
  }

  @PostMapping("/import/check")
  public ApiResponse<ConfigSyncImportCheckDTO> checkImport(
      @RequestBody ConfigSyncImportCheckRequestDTO request) {
    return ApiResponse.ok(configSyncService.checkImport(request));
  }

  @PostMapping("/import")
  public ApiResponse<ConfigSyncImportResultDTO> importYaml(
      @RequestBody ConfigSyncImportRequestDTO request) {
    return ApiResponse.ok(configSyncService.importYaml(request));
  }
}
