package fun.fengwk.kkstudio.platform.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import fun.fengwk.kkstudio.common.error.DomainErrorCode;
import fun.fengwk.kkstudio.common.error.DomainException;
import fun.fengwk.kkstudio.platform.persistence.test.PostgresSpringTestSupport;
import fun.fengwk.kkstudio.platform.settings.dto.SystemSettingsDTO;
import fun.fengwk.kkstudio.platform.settings.dto.SystemSettingsSectionsDTO;
import fun.fengwk.kkstudio.platform.settings.dto.SystemSettingsUpdateDTO;

/** System settings 服务契约：GET 默认聚合、PUT 完整替换 CAS、陈旧版本 409、非法聚合 400、live 快照 afterCommit 替换。 */
public class SystemSettingsServiceIntegrationTest extends PostgresSpringTestSupport {

  @Autowired private SystemSettingsService systemSettingsService;
  @Autowired private SystemSettingsCodec systemSettingsCodec;
  @Autowired private SystemSettingsSnapshot systemSettingsSnapshot;

  @Test
  public void getReturnsDefaultsWithVersionAndTimestamps() {
    SystemSettingsDTO dto = systemSettingsService.get();
    assertEquals(0L, dto.getVersion());
    assertNotNull(dto.getCreatedAt());
    assertNotNull(dto.getUpdatedAt());
    assertEquals(
        systemSettingsCodec.toSections(SystemSettingsTestFixture.DEFAULT), sectionsOf(dto));
    assertTrue(dto.getTool().getPermission().containsKey("bash"));
    assertEquals("ask", dto.getTool().getPermission().get("bash").get(0).getAction());
  }

  @Test
  public void updateReplacesTheCompleteAggregateWithCas() {
    SystemSettingsDTO current = systemSettingsService.get();

    SystemSettingsUpdateDTO update = updateFrom(current, 0L);
    update.getTool().setModelGatewayBusyRetryMillis(6000L);
    update.getAiRuntime().setCompactionKeepRecentTokens(8_192);
    update.getStorageMedia().setUploadExpiresSeconds(7200L);
    // 配置只持久化；此测试不连接代理或重启任何运行实例。
    update.getBackendNetwork().setProxyUrl("http://proxy:3128");
    update.getBackendNetwork().setNoProxyHosts("localhost,*.example.test");

    SystemSettingsDTO updated = systemSettingsService.update(update);
    assertEquals(1L, updated.getVersion());
    assertEquals(6000L, updated.getTool().getModelGatewayBusyRetryMillis());
    assertEquals(8_192, updated.getAiRuntime().getCompactionKeepRecentTokens());
    assertEquals(7200L, updated.getStorageMedia().getUploadExpiresSeconds());
    assertEquals("http://proxy:3128", updated.getBackendNetwork().getProxyUrl());
    // 未修改的 section 保持完整。
    assertEquals(
        systemSettingsCodec.toSections(SystemSettingsTestFixture.DEFAULT).getEnvironment(),
        updated.getEnvironment());

    // 再次读取与最新版本一致。
    SystemSettingsDTO reread = systemSettingsService.get();
    assertEquals(1L, reread.getVersion());
    assertEquals(updated.getBackendNetwork(), reread.getBackendNetwork());
  }

  /** PUT 成功后 live 快照必须在事务提交后替换为最新权威聚合；提交前仍是旧值。 */
  @Test
  public void updateReplacesLiveSnapshotAfterCommit() {
    SystemSettingsDTO current = systemSettingsService.get();
    SystemSettingsUpdateDTO update = updateFrom(current, 0L);
    update.getAiRuntime().setRetryMaxRetries(9);

    // 事务尚未提交：内存快照保持旧值。
    assertEquals(3, systemSettingsSnapshot.get().aiRuntime().retryMaxRetries());
    SystemSettingsDTO updated = systemSettingsService.update(update);
    // Spring 事务在 service 返回时已提交：快照已替换为最新权威聚合。
    assertEquals(1L, updated.getVersion());
    assertEquals(9, systemSettingsSnapshot.get().aiRuntime().retryMaxRetries());
  }

  @Test
  public void updateRejectsStaleAndMalformedVersions() {
    SystemSettingsDTO current = systemSettingsService.get();
    DomainException conflict =
        assertThrows(
            DomainException.class, () -> systemSettingsService.update(updateFrom(current, 7L)));
    assertEquals(DomainErrorCode.VERSION_CONFLICT, conflict.code());

    SystemSettingsUpdateDTO missing = updateFrom(current, null);
    assertEquals(
        DomainErrorCode.VALIDATION,
        assertThrows(DomainException.class, () -> systemSettingsService.update(missing)).code());

    // 非十进制文本在 HTTP 边界被拒绝；服务层只接受已解析的 Long。
  }

  @Test
  public void updateRejectsInvalidAggregateWithFieldQualifiedMessage() {
    SystemSettingsDTO current = systemSettingsService.get();
    SystemSettingsUpdateDTO update = updateFrom(current, 0L);
    update.getAdvanced().setProcessorHeartbeatIntervalMillis(60_000L);
    DomainException error =
        assertThrows(DomainException.class, () -> systemSettingsService.update(update));
    assertEquals(DomainErrorCode.VALIDATION, error.code());
    assertTrue(error.getMessage().contains("processorHeartbeatIntervalMillis"), error.getMessage());
  }

  @Test
  public void updateRejectsMissingSectionsAndUnknownRequestFields() {
    SystemSettingsDTO current = systemSettingsService.get();
    SystemSettingsUpdateDTO update = updateFrom(current, 0L);
    update.setBackendNetwork(null);
    DomainException error =
        assertThrows(DomainException.class, () -> systemSettingsService.update(update));
    assertEquals(DomainErrorCode.VALIDATION, error.code());
    assertTrue(error.getMessage().contains("backendNetwork is required"), error.getMessage());
  }

  private static SystemSettingsUpdateDTO updateFrom(
      SystemSettingsDTO current, Long expectedVersion) {
    SystemSettingsUpdateDTO update = new SystemSettingsUpdateDTO();
    update.setExpectedVersion(expectedVersion);
    update.setTool(current.getTool());
    update.setAiRuntime(current.getAiRuntime());
    update.setEnvironment(current.getEnvironment());
    update.setBackendNetwork(current.getBackendNetwork());
    update.setStorageMedia(current.getStorageMedia());
    update.setAdvanced(current.getAdvanced());
    return update;
  }

  private static SystemSettingsSectionsDTO sectionsOf(SystemSettingsDTO dto) {
    SystemSettingsSectionsDTO sections = new SystemSettingsSectionsDTO();
    sections.setTool(dto.getTool());
    sections.setAiRuntime(dto.getAiRuntime());
    sections.setEnvironment(dto.getEnvironment());
    sections.setBackendNetwork(dto.getBackendNetwork());
    sections.setStorageMedia(dto.getStorageMedia());
    sections.setAdvanced(dto.getAdvanced());
    return sections;
  }
}
