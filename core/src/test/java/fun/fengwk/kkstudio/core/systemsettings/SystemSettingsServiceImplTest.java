package fun.fengwk.kkstudio.core.systemsettings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.share.systemsettings.SystemSettingsSectionsDTO;
import fun.fengwk.kkstudio.share.systemsettings.SystemSettingsUpdateDTO;

import java.time.Instant;

/** System settings service 的条件更新竞争与单行缺失语义。 */
class SystemSettingsServiceImplTest {

  private static final Instant CREATED_AT = Instant.parse("2026-08-01T00:00:00Z");
  private static final Instant UPDATED_AT = Instant.parse("2026-08-01T00:00:01Z");

  private final SystemSettingsCodec codec = new SystemSettingsCodec();
  private final SystemSettingsRepository repository = mock(SystemSettingsRepository.class);
  private final SystemSettingsServiceImpl service =
      new SystemSettingsServiceImpl(repository, codec);

  /** 预检查后 CAS 败给并发写入时，409 必须携带重新读取到的当前版本。 */
  @Test
  void updateReportsRereadVersionWhenConditionalCasLoses() {
    when(repository.get()).thenReturn(record(0), record(1));
    when(repository.update(SystemSettings.DEFAULT, 0)).thenReturn(false);

    SystemSettingsVersionConflictException error =
        assertThrows(
            SystemSettingsVersionConflictException.class, () -> service.update(update("0")));

    assertEquals("0", error.expectedVersion());
    assertEquals("1", error.actualVersion());
    verify(repository).update(SystemSettings.DEFAULT, 0);
  }

  /** 条件更新失败后单行同时消失时，服务必须返回资源缺失而不是伪造版本冲突。 */
  @Test
  void updateReportsMissingRowWhenConditionalCasLoses() {
    when(repository.get()).thenReturn(record(0)).thenReturn(null);
    when(repository.update(SystemSettings.DEFAULT, 0)).thenReturn(false);

    assertThrows(SystemSettingsResourceNotFoundException.class, () -> service.update(update("0")));
  }

  /** GET 对 singleton 行缺失保持 fail-closed。 */
  @Test
  void getReportsMissingSingletonRow() {
    when(repository.get()).thenReturn(null);

    assertThrows(SystemSettingsResourceNotFoundException.class, service::get);
  }

  private SystemSettingsUpdateDTO update(String expectedVersion) {
    SystemSettingsSectionsDTO sections = codec.toSections(SystemSettings.DEFAULT);
    SystemSettingsUpdateDTO update = new SystemSettingsUpdateDTO();
    update.setExpectedVersion(expectedVersion);
    update.setTool(sections.getTool());
    update.setAiRuntime(sections.getAiRuntime());
    update.setEnvironment(sections.getEnvironment());
    update.setIntegrations(sections.getIntegrations());
    update.setStorageMedia(sections.getStorageMedia());
    update.setAdvanced(sections.getAdvanced());
    return update;
  }

  private static SystemSettingsRepository.SystemSettingsRecord record(long version) {
    return new SystemSettingsRepository.SystemSettingsRecord(
        SystemSettings.DEFAULT, version, CREATED_AT, UPDATED_AT);
  }
}
