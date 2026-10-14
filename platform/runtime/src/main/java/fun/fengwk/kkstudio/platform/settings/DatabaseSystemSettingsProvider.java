package fun.fengwk.kkstudio.platform.settings;

import lombok.AllArgsConstructor;
import org.springframework.stereotype.Component;

/** 基于 {@code system_setting(id=1)} 的权威配置提供者。 */
@AllArgsConstructor
@Component
public class DatabaseSystemSettingsProvider implements SystemSettingsProvider {

  private final SystemSettingsRepository systemSettingsRepository;

  @Override
  public SystemSettings get() {
    SystemSettingsRepository.SystemSettingsRecord record = systemSettingsRepository.get();
    if (record == null) {
      throw new IllegalStateException("system settings row is missing");
    }
    return record.settings();
  }
}
