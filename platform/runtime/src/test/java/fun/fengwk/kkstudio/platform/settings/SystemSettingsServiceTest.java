package fun.fengwk.kkstudio.platform.settings;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import fun.fengwk.kkstudio.common.error.DomainErrorCode;
import fun.fengwk.kkstudio.common.error.DomainException;
import fun.fengwk.kkstudio.platform.settings.dto.SystemSettingsSectionsDTO;
import fun.fengwk.kkstudio.platform.settings.dto.SystemSettingsUpdateDTO;

import java.time.Instant;

/** System settings service 的条件更新竞争、单行缺失语义与 live 快照 afterCommit/rollback 契约。 */
class SystemSettingsServiceTest {

  private static final Instant CREATED_AT = Instant.parse("2026-08-01T00:00:00Z");
  private static final Instant UPDATED_AT = Instant.parse("2026-08-01T00:00:01Z");

  /** 与默认聚合可区分的非默认权威聚合（version=1 行的内容）。 */
  private static final SystemSettings UPDATED_SETTINGS =
      new SystemSettings(
          SystemSettingsTestFixture.TOOL,
          new SystemSettings.AiRuntime(
              9,
              SystemSettingsTestFixture.AI_RUNTIME.retryBackoffStrategy(),
              SystemSettingsTestFixture.AI_RUNTIME.retryBaseDelayMillis(),
              SystemSettingsTestFixture.AI_RUNTIME.retryMaxDelayMillis(),
              SystemSettingsTestFixture.AI_RUNTIME.compactionModel(),
              SystemSettingsTestFixture.AI_RUNTIME.compactionKeepRecentTokens(),
              SystemSettingsTestFixture.AI_RUNTIME.subagentMaxDepth(),
              SystemSettingsTestFixture.AI_RUNTIME.subagentMaxConcurrency(),
              SystemSettingsTestFixture.AI_RUNTIME.subagentMaxTotalConcurrency(),
              SystemSettingsTestFixture.AI_RUNTIME.subagentMaxTurns(),
              SystemSettingsTestFixture.AI_RUNTIME.modelHttpRetryStatusCodes()),
          SystemSettingsTestFixture.ENVIRONMENT,
          SystemSettingsTestFixture.BACKEND_NETWORK,
          SystemSettingsTestFixture.STORAGE_MEDIA,
          SystemSettingsTestFixture.ADVANCED);

  private final SystemSettingsCodec codec = new SystemSettingsCodec();
  private final SystemSettingsRepository repository = mock(SystemSettingsRepository.class);
  private final SystemSettingsSnapshot snapshot =
      new SystemSettingsSnapshot(SystemSettingsTestFixture.DEFAULT);
  private final SystemSettingsChangeHandler changeHandler =
      new SystemSettingsChangeHandler(repository, snapshot);
  private final SystemSettingsService service =
      new SystemSettingsService(repository, codec, changeHandler);

  @AfterEach
  void clearSynchronization() {
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.clearSynchronization();
    }
  }

  /** 预检查后 CAS 败给并发写入时，409 必须携带重新读取到的当前版本。 */
  @Test
  void updateReportsRereadVersionWhenConditionalCasLoses() {
    when(repository.get()).thenReturn(record(0), record(1));
    when(repository.update(SystemSettingsTestFixture.DEFAULT, 0)).thenReturn(false);

    DomainException error = assertThrows(DomainException.class, () -> service.update(update(0L)));

    assertEquals(DomainErrorCode.VERSION_CONFLICT, error.code());
    assertEquals(0L, error.expectedVersion());
    assertEquals(1L, error.actualVersion());
    verify(repository).update(SystemSettingsTestFixture.DEFAULT, 0);
  }

  /** 条件更新失败后单行同时消失时，服务必须返回资源缺失而不是伪造版本冲突。 */
  @Test
  void updateReportsMissingRowWhenConditionalCasLoses() {
    when(repository.get()).thenReturn(record(0)).thenReturn(null);
    when(repository.update(SystemSettingsTestFixture.DEFAULT, 0)).thenReturn(false);

    DomainException error = assertThrows(DomainException.class, () -> service.update(update(0L)));
    assertEquals(DomainErrorCode.NOT_FOUND, error.code());
  }

  /** GET 对 singleton 行缺失保持 fail-closed。 */
  @Test
  void getReportsMissingSingletonRow() {
    when(repository.get()).thenReturn(null);

    DomainException error = assertThrows(DomainException.class, service::get);
    assertEquals(DomainErrorCode.NOT_FOUND, error.code());
  }

  /** afterCommit 后才替换 live 快照：事务提交前内存仍为旧值，提交后为回读的权威新值。 */
  @Test
  void replacesLiveSnapshotOnlyAfterCommit() {
    when(repository.get()).thenReturn(record(0), record(0), record(1));
    when(repository.update(SystemSettingsTestFixture.DEFAULT, 0)).thenReturn(true);

    TransactionSynchronizationManager.initSynchronization();
    service.update(update(0L));
    // 事务尚未提交：内存快照不得提前更新。
    assertEquals(SystemSettingsTestFixture.DEFAULT, snapshot.get());
    TransactionSynchronizationManager.getSynchronizations()
        .forEach(TransactionSynchronization::afterCommit);
    // afterCommit 回读权威行并替换。
    assertEquals(UPDATED_SETTINGS, snapshot.get());
  }

  /** 事务回滚（只走 afterCompletion 且未提交）绝不更新内存快照。 */
  @Test
  void rollbackNeverUpdatesLiveSnapshot() {
    when(repository.get()).thenReturn(record(0), record(0));
    when(repository.update(SystemSettingsTestFixture.DEFAULT, 0)).thenReturn(true);

    TransactionSynchronizationManager.initSynchronization();
    service.update(update(0L));
    // 模拟回滚：只触发 afterCompletion(STATUS_ROLLED_BACK)，不触发 afterCommit。
    TransactionSynchronizationManager.getSynchronizations()
        .forEach(
            synchronization ->
                synchronization.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
    assertEquals(SystemSettingsTestFixture.DEFAULT, snapshot.get());
  }

  /** 数据库已经提交后，afterCommit 权威回读短暂失败不得向 API 线程冒泡或写入错误快照；后续通知仍可恢复到最新版本。 */
  @Test
  void afterCommitRefreshFailureDoesNotEscapeAndLaterNotificationRecovers() {
    when(repository.get())
        .thenReturn(record(0), record(1))
        .thenThrow(new IllegalStateException("database temporarily unavailable"))
        .thenReturn(record(1));
    when(repository.update(SystemSettingsTestFixture.DEFAULT, 0)).thenReturn(true);

    TransactionSynchronizationManager.initSynchronization();
    assertEquals(1L, service.update(update(0L)).getVersion());
    assertDoesNotThrow(
        () ->
            TransactionSynchronizationManager.getSynchronizations()
                .forEach(TransactionSynchronization::afterCommit));
    assertEquals(SystemSettingsTestFixture.DEFAULT, snapshot.get());

    changeHandler.onNotification("");

    assertEquals(UPDATED_SETTINGS, snapshot.get());
  }

  /** 无活跃事务同步的调用路径（纯单元测试）直接回读替换，保证快照与数据库一致。 */
  @Test
  void replacesSnapshotDirectlyWithoutActiveSynchronization() {
    when(repository.get()).thenReturn(record(0), record(0), record(1));
    when(repository.update(SystemSettingsTestFixture.DEFAULT, 0)).thenReturn(true);

    service.update(update(0L));

    assertEquals(UPDATED_SETTINGS, snapshot.get());
  }

  /** 无事务同步路径的提交后刷新失败同样不得改变已完成写结果或写入错误快照。 */
  @Test
  void refreshFailureWithoutActiveSynchronizationDoesNotChangeWriteResult() {
    when(repository.get())
        .thenReturn(record(0), record(1))
        .thenThrow(new IllegalStateException("database temporarily unavailable"));
    when(repository.update(SystemSettingsTestFixture.DEFAULT, 0)).thenReturn(true);

    assertEquals(1L, assertDoesNotThrow(() -> service.update(update(0L))).getVersion());
    assertEquals(SystemSettingsTestFixture.DEFAULT, snapshot.get());
  }

  private SystemSettingsUpdateDTO update(Long expectedVersion) {
    SystemSettingsSectionsDTO sections = codec.toSections(SystemSettingsTestFixture.DEFAULT);
    return update(sections, expectedVersion);
  }

  /** 非法代理在任何仓库读写之前拒绝，快照保持原值，异常不回显凭据。 */
  @Test
  void invalidProxyFailsBeforeRepositoryAccess() {
    SystemSettingsUpdateDTO request = update(0L);
    request.getBackendNetwork().setProxyUrl("http://synthetic-user:synthetic-password@proxy:3128");
    DomainException error = assertThrows(DomainException.class, () -> service.update(request));
    assertEquals(DomainErrorCode.VALIDATION, error.code());
    assertEquals("backendNetwork.proxyUrl is invalid", error.getMessage());
    verifyNoInteractions(repository);
    assertEquals(SystemSettingsTestFixture.DEFAULT, snapshot.get());
  }

  /** GET 映射完整聚合及版本，backendNetwork 的可选字段也必须保留。 */
  @Test
  void getIncludesPersistedBackendNetworkSection() {
    SystemSettingsSectionsDTO sections = codec.toSections(SystemSettingsTestFixture.DEFAULT);
    sections.getBackendNetwork().setProxyUrl("http://proxy:3128");
    sections.getBackendNetwork().setNoProxyHosts("");
    SystemSettings settings = codec.fromDto(sections);
    when(repository.get())
        .thenReturn(
            new SystemSettingsRepository.SystemSettingsRecord(settings, 1, CREATED_AT, UPDATED_AT));
    var response = service.get();
    assertEquals(settings, codec.fromDto(response));
    assertEquals(1L, response.getVersion());
    assertEquals(CREATED_AT, response.getCreatedAt());
    assertEquals(UPDATED_AT, response.getUpdatedAt());
  }

  private SystemSettingsUpdateDTO update(SystemSettingsSectionsDTO sections, Long expectedVersion) {
    SystemSettingsUpdateDTO update = new SystemSettingsUpdateDTO();
    update.setExpectedVersion(expectedVersion);
    update.setTool(sections.getTool());
    update.setAiRuntime(sections.getAiRuntime());
    update.setEnvironment(sections.getEnvironment());
    update.setBackendNetwork(sections.getBackendNetwork());
    update.setStorageMedia(sections.getStorageMedia());
    update.setAdvanced(sections.getAdvanced());
    return update;
  }

  private static SystemSettingsRepository.SystemSettingsRecord record(long version) {
    return new SystemSettingsRepository.SystemSettingsRecord(
        version == 1 ? UPDATED_SETTINGS : SystemSettingsTestFixture.DEFAULT,
        version,
        CREATED_AT,
        UPDATED_AT);
  }
}
