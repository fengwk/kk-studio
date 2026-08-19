package fun.fengwk.kkstudio.core.systemsettings;

import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import fun.fengwk.kkstudio.share.systemsettings.SystemSettingsDTO;
import fun.fengwk.kkstudio.share.systemsettings.SystemSettingsSectionsDTO;
import fun.fengwk.kkstudio.share.systemsettings.SystemSettingsUpdateDTO;

/** 单行 system settings 的 CAS 读写；数据库行是权威，服务只在读回时严格解码并校验。 */
@AllArgsConstructor
@Service
public class SystemSettingsServiceImpl implements SystemSettingsService {

  private static final String RESOURCE = "system_settings";

  private final SystemSettingsRepository systemSettingsRepository;
  private final SystemSettingsCodec systemSettingsCodec;

  @Override
  public SystemSettingsDTO get() {
    return toDto(requireRecord());
  }

  @Override
  @Transactional
  public SystemSettingsDTO update(SystemSettingsUpdateDTO updateDTO) {
    String rawExpected = updateDTO == null ? null : updateDTO.getExpectedVersion();
    if (rawExpected == null) {
      throw new SystemSettingsValidationException(RESOURCE, "expectedVersion is required");
    }
    long expected = SystemSettingsVersions.parse(rawExpected, "expectedVersion");
    SystemSettings settings;
    try {
      settings = systemSettingsCodec.fromDto(updateDTO);
    } catch (IllegalArgumentException error) {
      throw new SystemSettingsValidationException(RESOURCE, error.getMessage(), error);
    }

    SystemSettingsRepository.SystemSettingsRecord current = requireRecord();
    if (current.version() != expected) {
      throw new SystemSettingsVersionConflictException(
          RESOURCE, rawExpected, SystemSettingsVersions.format(current.version()));
    }
    if (!systemSettingsRepository.update(settings, expected)) {
      SystemSettingsRepository.SystemSettingsRecord reread = systemSettingsRepository.get();
      if (reread == null) {
        throw new SystemSettingsResourceNotFoundException(
            RESOURCE, "system settings row is missing");
      }
      throw new SystemSettingsVersionConflictException(
          RESOURCE, rawExpected, SystemSettingsVersions.format(reread.version()));
    }
    return toDto(requireRecord());
  }

  private SystemSettingsRepository.SystemSettingsRecord requireRecord() {
    SystemSettingsRepository.SystemSettingsRecord record = systemSettingsRepository.get();
    if (record == null) {
      throw new SystemSettingsResourceNotFoundException(RESOURCE, "system settings row is missing");
    }
    return record;
  }

  private SystemSettingsDTO toDto(SystemSettingsRepository.SystemSettingsRecord record) {
    SystemSettingsSectionsDTO sections = systemSettingsCodec.toSections(record.settings());
    SystemSettingsDTO dto = new SystemSettingsDTO();
    dto.setTool(sections.getTool());
    dto.setAiRuntime(sections.getAiRuntime());
    dto.setEnvironment(sections.getEnvironment());
    dto.setIntegrations(sections.getIntegrations());
    dto.setStorageMedia(sections.getStorageMedia());
    dto.setAdvanced(sections.getAdvanced());
    dto.setVersion(SystemSettingsVersions.format(record.version()));
    dto.setCreateTime(record.createTime());
    dto.setUpdateTime(record.updateTime());
    return dto;
  }
}
