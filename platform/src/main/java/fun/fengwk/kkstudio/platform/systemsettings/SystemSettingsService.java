package fun.fengwk.kkstudio.platform.systemsettings;

import fun.fengwk.kkstudio.share.systemsettings.SystemSettingsDTO;
import fun.fengwk.kkstudio.share.systemsettings.SystemSettingsUpdateDTO;

/** 全局 system settings 聚合的读取与 CAS 替换。 */
public interface SystemSettingsService {

  /** 读取当前聚合（含版本与时戳）。 */
  SystemSettingsDTO get();

  /** 以 {@code expectedVersion} CAS 整体替换聚合；版本竞争抛 {@link SystemSettingsVersionConflictException}。 */
  SystemSettingsDTO update(SystemSettingsUpdateDTO updateDTO);
}
