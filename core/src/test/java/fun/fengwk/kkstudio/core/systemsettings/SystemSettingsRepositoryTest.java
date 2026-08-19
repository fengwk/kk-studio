package fun.fengwk.kkstudio.core.systemsettings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import fun.fengwk.kkstudio.core.persistence.test.PostgresSpringTestSupport;
import fun.fengwk.kkstudio.harness.runtime.permission.PermissionAction;
import fun.fengwk.kkstudio.harness.runtime.permission.PermissionRule;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
    // V1 默认行字面量必须与 codec 输出的 canonical JSON 完全一致（键排序 + 省略 null + 小写 action）。
    // 直接读取迁移文件校验字面量，因为 PostgreSQL jsonb 的文本输出会插入空格，不能与 canonical 字符串比对。
    String literal = seedLiteralFromV1();
    assertEquals(
        systemSettingsCodec.encode(SystemSettings.DEFAULT),
        literal,
        "V1 seed literal must equal the canonical defaults");
    // 默认行必须可解码为安全默认聚合（语义往返）。
    String stored = systemSettingsMapper.get().getConfigJson();
    assertEquals(systemSettingsCodec.decode(stored), SystemSettings.DEFAULT);
  }

  /** 从 V1 迁移文件提取 system_setting 默认行的 canonical JSON 字面量。 */
  private static String seedLiteralFromV1() {
    try (InputStream in =
        SystemSettingsRepositoryTest.class.getResourceAsStream("/db/migration/V1__schema.sql")) {
      String sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
      Matcher matcher =
          Pattern.compile(
                  "(?s)"
                      + "insert into system_setting \\(id, config\\) values \\(\\n\\s+1,\\n\\s+'"
                      + "(\\{.*?})'::jsonb")
              .matcher(sql);
      assertTrue(matcher.find(), "system_setting default row literal must exist in V1");
      return matcher.group(1);
    } catch (IOException error) {
      throw new IllegalStateException("failed to read V1__schema.sql", error);
    }
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
        withPermission(Map.of("write", List.of(new PermissionRule("*", PermissionAction.DENY))));

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
                  == reread.settings().tool().permission().get("write").get(0).action(),
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
