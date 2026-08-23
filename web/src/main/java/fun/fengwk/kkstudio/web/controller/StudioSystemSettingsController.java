package fun.fengwk.kkstudio.web.controller;

import fun.fengwk.convention4j.api.result.Result;
import fun.fengwk.convention4j.common.result.Results;
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

/** 全局 system settings 聚合的 GET / PUT HTTP 边界。 */
@AllArgsConstructor
@RequestMapping("/api/settings")
@RestController
public class StudioSystemSettingsController {

  private final SystemSettingsService systemSettingsService;
  private final SystemSettingsSchemaProvider systemSettingsSchemaProvider;

  @GetMapping
  public Result<SystemSettingsDTO> get() {
    return Results.ok(systemSettingsService.get());
  }

  @GetMapping("/schema")
  public Result<SystemSettingsSchemaDTO> schema() {
    return Results.ok(systemSettingsSchemaProvider.get());
  }

  @PutMapping
  public Result<SystemSettingsDTO> update(@RequestBody SystemSettingsUpdateDTO updateDTO) {
    return Results.ok(systemSettingsService.update(updateDTO));
  }
}
