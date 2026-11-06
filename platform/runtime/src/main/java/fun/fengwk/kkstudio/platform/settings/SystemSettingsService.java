package fun.fengwk.kkstudio.platform.settings;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import fun.fengwk.kkstudio.common.error.DomainException;
import fun.fengwk.kkstudio.platform.settings.dto.SystemSettingsDTO;
import fun.fengwk.kkstudio.platform.settings.dto.SystemSettingsUpdateDTO;

import java.util.Objects;

/**
 * 单行 system settings 的 CAS 读写；数据库行是权威，服务只在读回时严格解码并校验。
 *
 * <p>PUT 成功后不直接更新内存：先在 {@code afterCommit} 回调中通过 {@link SystemSettingsChangeHandler} 容错回读权威记录，
 * 再按版本原子替换 {@link SystemSettingsSnapshot}。事务回滚绝不触碰内存快照；提交后的刷新失败不得向调用方冒泡。无活跃事务同步 （纯单元测试路径）时使用相同刷新语义。
 */
@Service
public class SystemSettingsService {

  private static final String RESOURCE = "system_settings";

  private final SystemSettingsRepository systemSettingsRepository;
  private final SystemSettingsCodec systemSettingsCodec;
  private final SystemSettingsChangeHandler systemSettingsChangeHandler;

  public SystemSettingsService(
      SystemSettingsRepository systemSettingsRepository,
      SystemSettingsCodec systemSettingsCodec,
      SystemSettingsChangeHandler systemSettingsChangeHandler) {
    this.systemSettingsRepository = Objects.requireNonNull(systemSettingsRepository, "repository");
    this.systemSettingsCodec = Objects.requireNonNull(systemSettingsCodec, "codec");
    this.systemSettingsChangeHandler =
        Objects.requireNonNull(systemSettingsChangeHandler, "changeHandler");
  }

  /** 读取当前聚合（含版本与时戳）。 */
  public SystemSettingsDTO get() {
    return toDto(requireRecord());
  }

  /** 以 {@code expectedVersion} CAS 整体替换聚合；版本竞争抛领域异常。 */
  @Transactional
  public SystemSettingsDTO update(SystemSettingsUpdateDTO updateDTO) {
    Long expected = updateDTO == null ? null : updateDTO.getExpectedVersion();
    if (expected == null) {
      throw DomainException.validation(RESOURCE, "expectedVersion is required");
    }
    SystemSettings settings;
    try {
      settings = systemSettingsCodec.fromDto(updateDTO);
    } catch (IllegalArgumentException error) {
      throw DomainException.validation(RESOURCE, error.getMessage(), error);
    }

    SystemSettingsRepository.SystemSettingsRecord current = requireRecord();
    if (current.version() != expected) {
      throw DomainException.versionConflict(
          RESOURCE, Long.toString(expected), Long.toString(current.version()));
    }
    if (!systemSettingsRepository.update(settings, expected)) {
      SystemSettingsRepository.SystemSettingsRecord reread = systemSettingsRepository.get();
      if (reread == null) {
        throw DomainException.notFound(RESOURCE, "system settings row is missing");
      }
      throw DomainException.versionConflict(
          RESOURCE, Long.toString(expected), Long.toString(reread.version()));
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
      throw DomainException.notFound(RESOURCE, "system settings row is missing");
    }
    return record;
  }

  private SystemSettingsDTO toDto(SystemSettingsRepository.SystemSettingsRecord record) {
    SystemSettingsDTO dto =
        systemSettingsCodec.toSections(record.settings(), new SystemSettingsDTO());
    dto.setVersion(record.version());
    dto.setCreatedAt(record.createdAt());
    dto.setUpdatedAt(record.updatedAt());
    return dto;
  }
}
