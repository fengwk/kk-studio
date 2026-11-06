package fun.fengwk.kkstudio.platform.configsync.http;

import lombok.AllArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import fun.fengwk.kkstudio.platform.configsync.ConfigSyncService;
import fun.fengwk.kkstudio.platform.configsync.dto.ConfigSyncExportDTO;
import fun.fengwk.kkstudio.platform.configsync.dto.ConfigSyncExportRequestDTO;
import fun.fengwk.kkstudio.platform.configsync.dto.ConfigSyncImportCheckDTO;
import fun.fengwk.kkstudio.platform.configsync.dto.ConfigSyncImportCheckRequestDTO;
import fun.fengwk.kkstudio.platform.configsync.dto.ConfigSyncImportRequestDTO;
import fun.fengwk.kkstudio.platform.configsync.dto.ConfigSyncImportResultDTO;
import fun.fengwk.kkstudio.platform.configsync.dto.ConfigSyncInventoryDTO;

/** 配置同步 HTTP 边界；响应缓存由 ConfigSyncNoStoreFilter 统一禁止。 */
@AllArgsConstructor
@RequestMapping("/api/platform/config-sync")
@RestController
public class ConfigSyncController {

  private final ConfigSyncService configSyncService;

  @GetMapping
  public ConfigSyncInventoryDTO inventory() {
    return configSyncService.inventory();
  }

  @PostMapping("/export")
  public ConfigSyncExportDTO export(@RequestBody ConfigSyncExportRequestDTO request) {
    return configSyncService.export(request);
  }

  @PostMapping("/check-import")
  public ConfigSyncImportCheckDTO checkImport(
      @RequestBody ConfigSyncImportCheckRequestDTO request) {
    return configSyncService.checkImport(request);
  }

  @PostMapping("/import")
  public ConfigSyncImportResultDTO importYaml(@RequestBody ConfigSyncImportRequestDTO request) {
    return configSyncService.importYaml(request);
  }
}
