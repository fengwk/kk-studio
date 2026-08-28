package fun.fengwk.kkstudio.platform.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import fun.fengwk.kkstudio.harness.builtin.BuiltinToolIds;
import fun.fengwk.kkstudio.harness.runtime.permission.PermissionAction;
import fun.fengwk.kkstudio.harness.runtime.permission.PermissionRule;
import fun.fengwk.kkstudio.platform.persistence.test.PostgresSpringTestSupport;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/** System settings 仓库契约：baseline 默认行可读，CAS 更新恰好一个版本赢家，并发更新恰好一个成功。 */
public class SystemSettingsRepositoryTest extends PostgresSpringTestSupport {

  @Autowired private SystemSettingsRepository systemSettingsRepository;
  @Autowired private SystemSettingsMapper systemSettingsMapper;
  @Autowired private SystemSettingsCodec systemSettingsCodec;

  @Test
  public void baselineRowDecodesToDefaultsWithVersionZero() {
    SystemSettingsRepository.SystemSettingsRecord record = systemSettingsRepository.get();
    assertNotNull(record);
    assertEquals(SystemSettings.DEFAULT, record.settings());
    assertEquals(0L, record.version());
    assertNotNull(record.createTime());
    assertNotNull(record.updateTime());
  }

  @Test
  public void baselineRowConfigIsExactCanonicalJson() {
    // V1 直接定义当前最终结构，baseline 行必须等于当前 canonical 默认。
    // PostgreSQL jsonb 文本会插入空格，因此用 decode 比对语义。
    String stored = systemSettingsMapper.get().getConfigJson();
    assertFalse(
        stored.contains("\"maxMessageBytes\""),
        "V1 baseline must not persist environment.maxMessageBytes");
    assertEquals(systemSettingsCodec.decode(stored), SystemSettings.DEFAULT);
    assertFalse(
        systemSettingsCodec.encode(SystemSettings.DEFAULT).contains("maxMessageBytes"),
        "canonical defaults must not persist environment.maxMessageBytes");
  }

  @Test
  public void casUpdatePersistsCanonicalConfigAndBumpsVersion() {
    SystemSettings modified = withYolo(true);

    assertTrue(systemSettingsRepository.update(modified, 0L));
    SystemSettingsRepository.SystemSettingsRecord updated = systemSettingsRepository.get();
    assertEquals(1L, updated.version());
    assertEquals(modified, updated.settings());
    assertTrue(updated.settings().tool().defaultYolo(), "yolo flag must be persisted");

    assertFalse(systemSettingsRepository.update(modified, 0L), "stale version must lose CAS");
    assertTrue(systemSettingsRepository.update(modified, 1L));
    assertEquals(2L, systemSettingsRepository.get().version());
  }

  @Test
  public void concurrentCasAllowsExactlyOneWinner() throws Exception {
    SystemSettings first = withYolo(true);
    SystemSettings second =
        withPermission(
            Map.of(
                BuiltinToolIds.WRITE.value(),
                List.of(new PermissionRule("*", PermissionAction.DENY))));

    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      Future<Boolean> firstResult =
          executor.submit(
              () -> {
                ready.countDown();
                start.await();
                return systemSettingsRepository.update(first, 0L);
              });
      Future<Boolean> secondResult =
          executor.submit(
              () -> {
                ready.countDown();
                start.await();
                return systemSettingsRepository.update(second, 0L);
              });
      assertTrue(ready.await(5, TimeUnit.SECONDS));
      start.countDown();

      int winners = (firstResult.get() ? 1 : 0) + (secondResult.get() ? 1 : 0);
      assertEquals(1, winners, "exactly one concurrent CAS update must win");
      SystemSettingsRepository.SystemSettingsRecord reread = systemSettingsRepository.get();
      assertEquals(1L, reread.version());
      assertTrue(
          reread.settings().tool().defaultYolo()
              || PermissionAction.DENY
                  == reread
                      .settings()
                      .tool()
                      .permission()
                      .get(BuiltinToolIds.WRITE.value())
                      .get(0)
                      .action(),
          "winner's config must be persisted");
    } finally {
      executor.shutdownNow();
    }
  }

  private static SystemSettings withYolo(boolean yolo) {
    SystemSettings.Tool base = SystemSettings.DEFAULT.tool();
    return new SystemSettings(
        new SystemSettings.Tool(
            base.permission(),
            yolo,
            base.modelGatewayBusyRetryMillis(),
            base.toolGatewayBusyRetryMillis(),
            base.toolGatewayOverloadRetryMillis(),
            base.skillLoadTimeoutMillis()),
        SystemSettings.DEFAULT.aiRuntime(),
        SystemSettings.DEFAULT.environment(),
        SystemSettings.DEFAULT.integrations(),
        SystemSettings.DEFAULT.storageMedia(),
        SystemSettings.DEFAULT.advanced());
  }

  private static SystemSettings withPermission(Map<String, List<PermissionRule>> permission) {
    Map<String, List<PermissionRule>> merged =
        new LinkedHashMap<>(SystemSettings.DEFAULT.tool().permission());
    merged.putAll(permission);
    SystemSettings.Tool base = SystemSettings.DEFAULT.tool();
    return new SystemSettings(
        new SystemSettings.Tool(
            merged,
            base.defaultYolo(),
            base.modelGatewayBusyRetryMillis(),
            base.toolGatewayBusyRetryMillis(),
            base.toolGatewayOverloadRetryMillis(),
            base.skillLoadTimeoutMillis()),
        SystemSettings.DEFAULT.aiRuntime(),
        SystemSettings.DEFAULT.environment(),
        SystemSettings.DEFAULT.integrations(),
        SystemSettings.DEFAULT.storageMedia(),
        SystemSettings.DEFAULT.advanced());
  }
}
