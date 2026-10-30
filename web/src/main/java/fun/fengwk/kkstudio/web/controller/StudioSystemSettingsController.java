package fun.fengwk.kkstudio.web.controller;

import lombok.AllArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import fun.fengwk.kkstudio.platform.settings.SystemSettingsSchemaProvider;
import fun.fengwk.kkstudio.platform.settings.SystemSettingsService;
import fun.fengwk.kkstudio.share.systemsettings.SystemSettingsDTO;
import fun.fengwk.kkstudio.share.systemsettings.SystemSettingsSchemaDTO;
import fun.fengwk.kkstudio.share.systemsettings.SystemSettingsUpdateDTO;
import fun.fengwk.kkstudio.web.api.ApiResponse;

/** 全局 system settings 聚合的 GET / PUT HTTP 边界。 */
@AllArgsConstructor
@RequestMapping("/api/settings")
@RestController
public class StudioSystemSettingsController {

  private final SystemSettingsService systemSettingsService;
  private final SystemSettingsSchemaProvider systemSettingsSchemaProvider;

  @GetMapping
  public ApiResponse<SystemSettingsDTO> get() {
    return ApiResponse.ok(systemSettingsService.get());
  }

  @GetMapping("/schema")
  public ApiResponse<SystemSettingsSchemaDTO> schema() {
    return ApiResponse.ok(systemSettingsSchemaProvider.get());
  }

  @PutMapping
  public ApiResponse<SystemSettingsDTO> update(@RequestBody SystemSettingsUpdateDTO updateDTO) {
    return ApiResponse.ok(systemSettingsService.update(updateDTO));
  }
}
