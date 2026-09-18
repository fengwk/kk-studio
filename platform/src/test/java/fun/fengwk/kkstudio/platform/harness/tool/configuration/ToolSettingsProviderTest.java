package fun.fengwk.kkstudio.platform.harness.tool.configuration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.permission.PermissionAction;
import fun.fengwk.kkstudio.harness.runtime.permission.PermissionRule;
import fun.fengwk.kkstudio.harness.runtime.permission.ToolSettings;
import fun.fengwk.kkstudio.harness.runtime.permission.ToolSettingsProvider;
import fun.fengwk.kkstudio.platform.settings.SystemSettings;
import fun.fengwk.kkstudio.platform.settings.SystemSettingsProvider;

import java.util.List;
import java.util.Map;

class ToolSettingsProviderTest {

  /** 默认 permission 规则：write/edit/bash 各 {@code * -> ask}（read 保持不限制）。 */
  private static final List<PermissionRule> ASK_ALL =
      List.of(new PermissionRule("*", PermissionAction.ASK));

  /** 数据库默认聚合的 permission 为 write/edit/bash 的 {@code * -> ask}，read 不受限，且 defaultYolo=false。 */
  @Test
  void convertsDatabaseToolSectionAndSafeDefaults() {
    ToolSettingsProvider provider = providerFor(SystemSettings.Tool.DEFAULT);

    ToolSettings settings = provider.get();
    assertEquals(ASK_ALL, settings.rulesFor("write"));
    assertEquals(ASK_ALL, settings.rulesFor("edit"));
    assertEquals(ASK_ALL, settings.rulesFor("bash"));
    assertTrue(settings.rulesFor("read").isEmpty(), "production default must not restrict read");
    assertFalse(settings.defaultYolo());
  }

  /** 每次 {@code get()} 都读取当前数据库快照：defaultYolo 与 permission 的变更对下一次调用立即生效。 */
  @Test
  void reflectsCurrentDatabaseSnapshotOnEveryCall() {
    SystemSettingsProvider systemSettingsProvider = mock(SystemSettingsProvider.class);
    ToolSettingsProvider provider = new SystemSettingsToolSettingsProvider(systemSettingsProvider);

    when(systemSettingsProvider.get())
        .thenReturn(
            systemSettingsWithTool(
                new SystemSettings.Tool(
                    Map.of("write", List.of(new PermissionRule("*", PermissionAction.DENY))),
                    true,
                    5_000L,
                    1_000L,
                    5_000L,
                    30_000L)));
    assertTrue(provider.get().defaultYolo());
    assertEquals(PermissionAction.DENY, provider.get().rulesFor("write").getFirst().action());

    // 同一 provider 在数据库快照更新后无需重建即可读到新值。
    when(systemSettingsProvider.get())
        .thenReturn(systemSettingsWithTool(SystemSettings.Tool.DEFAULT));
    assertFalse(provider.get().defaultYolo());
    assertEquals(PermissionAction.ASK, provider.get().rulesFor("bash").getFirst().action());
    assertTrue(provider.get().rulesFor("read").isEmpty());
  }

  /** ACCEPTANCE: 转换直接复用数据库已校验的 permission 规则类型，不引入第二套解码/默认。 */
  @Test
  void delegatesToTheDatabaseAggregateWithoutACodec() {
    ToolSettings settings = providerFor(SystemSettings.Tool.DEFAULT).get();

    // SystemSettings.Tool.permission 与 ToolSettings.permission 是同一规则模型；转换后规则完全等价。
    assertEquals(
        SystemSettings.Tool.DEFAULT.permission().get("bash"),
        settings.rulesFor("bash"),
        "permission rules must come from the DB aggregate, no second permission source");
  }

  private static ToolSettingsProvider providerFor(SystemSettings.Tool tool) {
    SystemSettingsProvider systemSettingsProvider = mock(SystemSettingsProvider.class);
    when(systemSettingsProvider.get()).thenReturn(systemSettingsWithTool(tool));
    return new SystemSettingsToolSettingsProvider(systemSettingsProvider);
  }

  private static SystemSettings systemSettingsWithTool(SystemSettings.Tool tool) {
    return new SystemSettings(
        tool,
        SystemSettings.AiRuntime.DEFAULT,
        SystemSettings.Environment.DEFAULT,
        SystemSettings.Integrations.DEFAULT,
        SystemSettings.StorageMedia.DEFAULT,
        SystemSettings.Advanced.DEFAULT);
  }
}
