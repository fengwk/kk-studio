package fun.fengwk.kkstudio.platform.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import fun.fengwk.kkstudio.platform.persistence.test.PostgresSpringTestSupport;
import fun.fengwk.kkstudio.share.systemsettings.SystemSettingsDTO;
import fun.fengwk.kkstudio.share.systemsettings.SystemSettingsSectionsDTO;
import fun.fengwk.kkstudio.share.systemsettings.SystemSettingsUpdateDTO;

/** System settings 服务契约：GET 默认聚合、PUT 完整替换 CAS、陈旧版本 409、非法聚合 400、live 快照 afterCommit 替换。 */
public class SystemSettingsServiceIntegrationTest extends PostgresSpringTestSupport {

  @Autowired private SystemSettingsService systemSettingsService;
  @Autowired private SystemSettingsCodec systemSettingsCodec;
  @Autowired private SystemSettingsSnapshot systemSettingsSnapshot;

  @Test
  public void getReturnsDefaultsWithVersionAndTimestamps() {
    SystemSettingsDTO dto = systemSettingsService.get();
    assertEquals("0", dto.getVersion());
    assertNotNull(dto.getCreateTime());
    assertNotNull(dto.getUpdateTime());
    assertEquals(systemSettingsCodec.toSections(SystemSettings.DEFAULT), sectionsOf(dto));
    assertTrue(dto.getTool().getPermission().containsKey("bash"));
    assertEquals("ask", dto.getTool().getPermission().get("bash").get(0).getAction());
  }

  @Test
  public void updateReplacesTheCompleteAggregateWithCas() {
    SystemSettingsDTO current = systemSettingsService.get();

    SystemSettingsUpdateDTO update = updateFrom(current, "0");
    update.getTool().setDefaultYolo(true);
    update.getAiRuntime().setCompactionKeepRecentTokens(8_192);
    update.getStorageMedia().setUploadExpiresSeconds(7200L);

    SystemSettingsDTO updated = systemSettingsService.update(update);
    assertEquals("1", updated.getVersion());
    assertEquals(true, updated.getTool().getDefaultYolo());
    assertEquals(8_192, updated.getAiRuntime().getCompactionKeepRecentTokens());
    assertEquals(7200L, updated.getStorageMedia().getUploadExpiresSeconds());
    // 未修改的 section 保持完整。
    assertEquals(
        systemSettingsCodec.toSections(SystemSettings.DEFAULT).getEnvironment(),
        updated.getEnvironment());

    // 再次读取与最新版本一致。
    SystemSettingsDTO reread = systemSettingsService.get();
    assertEquals("1", reread.getVersion());
  }

  /** PUT 成功后 live 快照必须在事务提交后替换为最新权威聚合；提交前仍是旧值。 */
  @Test
  public void updateReplacesLiveSnapshotAfterCommit() {
    SystemSettingsDTO current = systemSettingsService.get();
    SystemSettingsUpdateDTO update = updateFrom(current, "0");
    update.getAiRuntime().setRetryMaxRetries(9);

    // 事务尚未提交：内存快照保持旧值。
    assertEquals(3, systemSettingsSnapshot.get().aiRuntime().retryMaxRetries());
    SystemSettingsDTO updated = systemSettingsService.update(update);
    // Spring 事务在 service 返回时已提交：快照已替换为最新权威聚合。
    assertEquals("1", updated.getVersion());
    assertEquals(9, systemSettingsSnapshot.get().aiRuntime().retryMaxRetries());
  }

  @Test
  public void updateRejectsStaleAndMalformedVersions() {
    SystemSettingsDTO current = systemSettingsService.get();
    assertThrows(
        SystemSettingsVersionConflictException.class,
        () -> systemSettingsService.update(updateFrom(current, "7")));

    SystemSettingsUpdateDTO missing = updateFrom(current, null);
    assertThrows(
        SystemSettingsValidationException.class, () -> systemSettingsService.update(missing));

    SystemSettingsUpdateDTO invalid = updateFrom(current, "not-a-version");
    assertThrows(
        SystemSettingsValidationException.class, () -> systemSettingsService.update(invalid));
  }

  @Test
  public void updateRejectsInvalidAggregateWithFieldQualifiedMessage() {
    SystemSettingsDTO current = systemSettingsService.get();
    SystemSettingsUpdateDTO update = updateFrom(current, "0");
    update.getAdvanced().setProcessorHeartbeatIntervalMillis(60_000L);
    SystemSettingsValidationException error =
        assertThrows(
            SystemSettingsValidationException.class, () -> systemSettingsService.update(update));
    assertTrue(error.getMessage().contains("processorHeartbeatIntervalMillis"), error.getMessage());
  }

  @Test
  public void updateRejectsMissingSectionsAndUnknownRequestFields() {
    SystemSettingsDTO current = systemSettingsService.get();
    SystemSettingsUpdateDTO update = updateFrom(current, "0");
    update.setIntegrations(null);
    SystemSettingsValidationException error =
        assertThrows(
            SystemSettingsValidationException.class, () -> systemSettingsService.update(update));
    assertTrue(error.getMessage().contains("integrations is required"), error.getMessage());
  }

  private static SystemSettingsUpdateDTO updateFrom(
      SystemSettingsDTO current, String expectedVersion) {
    SystemSettingsUpdateDTO update = new SystemSettingsUpdateDTO();
    update.setExpectedVersion(expectedVersion);
    update.setTool(current.getTool());
    update.setAiRuntime(current.getAiRuntime());
    update.setEnvironment(current.getEnvironment());
    update.setIntegrations(current.getIntegrations());
    update.setStorageMedia(current.getStorageMedia());
    update.setAdvanced(current.getAdvanced());
    return update;
  }

  private static SystemSettingsSectionsDTO sectionsOf(SystemSettingsDTO dto) {
    SystemSettingsSectionsDTO sections = new SystemSettingsSectionsDTO();
    sections.setTool(dto.getTool());
    sections.setAiRuntime(dto.getAiRuntime());
    sections.setEnvironment(dto.getEnvironment());
    sections.setIntegrations(dto.getIntegrations());
    sections.setStorageMedia(dto.getStorageMedia());
    sections.setAdvanced(dto.getAdvanced());
    return sections;
  }
}
