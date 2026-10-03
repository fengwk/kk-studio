package fun.fengwk.kkstudio.web.controller;

import fun.fengwk.convention4j.api.result.Result;
import fun.fengwk.convention4j.common.result.Results;
import lombok.AllArgsConstructor;
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

/** 配置同步 HTTP 边界；响应缓存由 ConfigSyncNoStoreFilter 统一禁止。 */
@AllArgsConstructor
@RequestMapping("/api/settings/sync")
@RestController
public class StudioConfigSyncController {

  private final ConfigSyncService configSyncService;

  @GetMapping
  public Result<ConfigSyncInventoryDTO> inventory() {
    return Results.ok(configSyncService.inventory());
  }

  @PostMapping("/export")
  public Result<ConfigSyncExportDTO> export(@RequestBody ConfigSyncExportRequestDTO request) {
    return Results.ok(configSyncService.export(request));
  }

  @PostMapping("/import")
  public Result<ConfigSyncImportResultDTO> importYaml(
      @RequestBody ConfigSyncImportRequestDTO request) {
    return Results.ok(configSyncService.importYaml(request));
  }
}
