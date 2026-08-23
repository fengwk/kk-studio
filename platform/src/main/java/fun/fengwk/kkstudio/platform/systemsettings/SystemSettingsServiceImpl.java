package fun.fengwk.kkstudio.platform.systemsettings;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import fun.fengwk.kkstudio.share.systemsettings.SystemSettingsDTO;
import fun.fengwk.kkstudio.share.systemsettings.SystemSettingsSectionsDTO;
import fun.fengwk.kkstudio.share.systemsettings.SystemSettingsUpdateDTO;

import java.util.Objects;

/**
 * 单行 system settings 的 CAS 读写；数据库行是权威，服务只在读回时严格解码并校验。
 *
 * <p>PUT 成功后不直接更新内存：先在 {@code afterCommit} 回调中通过 {@link SystemSettingsChangeHandler} 容错回读权威记录，
 * 再按版本原子替换 {@link SystemSettingsSnapshot}。事务回滚绝不触碰内存快照；提交后的刷新失败不得向调用方冒泡。无活跃事务同步 （纯单元测试路径）时使用相同刷新语义。
 */
@Service
public class SystemSettingsServiceImpl implements SystemSettingsService {

  private static final String RESOURCE = "system_settings";

  private final SystemSettingsRepository systemSettingsRepository;
  private final SystemSettingsCodec systemSettingsCodec;
  private final SystemSettingsChangeHandler systemSettingsChangeHandler;

  public SystemSettingsServiceImpl(
      SystemSettingsRepository systemSettingsRepository,
      SystemSettingsCodec systemSettingsCodec,
      SystemSettingsChangeHandler systemSettingsChangeHandler) {
    this.systemSettingsRepository = Objects.requireNonNull(systemSettingsRepository, "repository");
    this.systemSettingsCodec = Objects.requireNonNull(systemSettingsCodec, "codec");
    this.systemSettingsChangeHandler =
        Objects.requireNonNull(systemSettingsChangeHandler, "changeHandler");
  }

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
    SystemSettingsRepository.SystemSettingsRecord updated = requireRecord();
    refreshSnapshotAfterCommit();
    return toDto(updated);
  }

  /** 事务提交成功后用回读的权威聚合替换 live 快照；回滚（afterCompletion 未提交）不更新。 */
  private void refreshSnapshotAfterCommit() {
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.registerSynchronization(
          new TransactionSynchronization() {
            @Override
            public void afterCommit() {
              systemSettingsChangeHandler.onLocalCommit();
            }
          });
    } else {
      // 无事务同步（纯单元测试 / 非事务调用路径）：使用相同的容错权威刷新，不改变已完成写结果。
      systemSettingsChangeHandler.onLocalCommit();
    }
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
