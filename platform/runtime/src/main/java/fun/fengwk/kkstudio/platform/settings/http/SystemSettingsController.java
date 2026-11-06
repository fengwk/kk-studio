package fun.fengwk.kkstudio.platform.settings.http;

import lombok.AllArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import fun.fengwk.kkstudio.platform.settings.SystemSettingsSchemaProvider;
import fun.fengwk.kkstudio.platform.settings.SystemSettingsService;
import fun.fengwk.kkstudio.platform.settings.dto.SystemSettingsDTO;
import fun.fengwk.kkstudio.platform.settings.dto.SystemSettingsSchemaDTO;
import fun.fengwk.kkstudio.platform.settings.dto.SystemSettingsUpdateDTO;

/** 全局 system settings 聚合的 GET / PUT HTTP 边界。 */
@AllArgsConstructor
@RequestMapping("/api/platform/settings")
@RestController
public class SystemSettingsController {

  private final SystemSettingsService systemSettingsService;
  private final SystemSettingsSchemaProvider systemSettingsSchemaProvider;

  @GetMapping
  public SystemSettingsDTO get() {
    return systemSettingsService.get();
  }

  @GetMapping("/schema")
  public SystemSettingsSchemaDTO schema() {
    return systemSettingsSchemaProvider.get();
  }

  @PutMapping
  public SystemSettingsDTO update(@RequestBody SystemSettingsUpdateDTO updateDTO) {
    return systemSettingsService.update(updateDTO);
  }
}
