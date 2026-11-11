package fun.fengwk.kkstudio.platform.harness.tool.configuration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.platform.harness.tool.permission.PermissionAction;
import fun.fengwk.kkstudio.platform.harness.tool.permission.PermissionRule;
import fun.fengwk.kkstudio.platform.harness.tool.permission.ToolSettings;
import fun.fengwk.kkstudio.platform.harness.tool.permission.ToolSettingsProvider;
import fun.fengwk.kkstudio.platform.settings.SystemSettings;
import fun.fengwk.kkstudio.platform.settings.SystemSettingsRepository;
import fun.fengwk.kkstudio.platform.settings.SystemSettingsSnapshot;
import fun.fengwk.kkstudio.platform.settings.SystemSettingsTestFixture;

import java.util.List;
import java.util.Map;

class ToolSettingsProviderTest {

  /** 默认 permission 规则：write/edit/bash 各 {@code * -> ask}（read 保持不限制）。 */
  private static final List<PermissionRule> ASK_ALL =
      List.of(new PermissionRule("*", PermissionAction.ASK));

  /** 快照默认聚合的 permission 为 write/edit/bash 的 {@code * -> ask}，read 不受限。 */
  @Test
  void convertsSnapshotToolSectionAndSafeDefaults() {
    ToolSettingsProvider provider = providerFor(SystemSettingsTestFixture.TOOL);

    ToolSettings settings = provider.get();
    assertEquals(ASK_ALL, settings.rulesFor("write"));
    assertEquals(ASK_ALL, settings.rulesFor("edit"));
    assertEquals(ASK_ALL, settings.rulesFor("bash"));
    assertTrue(settings.rulesFor("read").isEmpty(), "production default must not restrict read");
  }

  /** 替换快照后 permission 对下一次 get 立即生效，且 provider 不会重复读取数据库。 */
  @Test
  void reflectsReplacedSnapshotWithoutRereadingDatabase() {
    SystemSettingsRepository repository = mock(SystemSettingsRepository.class);
    when(repository.get())
        .thenReturn(
            new SystemSettingsRepository.SystemSettingsRecord(
                SystemSettingsTestFixture.DEFAULT, 1L, null, null));
    SystemSettingsSnapshot snapshot = new SystemSettingsSnapshot(repository);
    ToolSettingsProvider provider = new SystemSettingsToolSettingsProvider(snapshot);

    assertEquals(PermissionAction.ASK, provider.get().rulesFor("write").getFirst().action());

    // 运行期快照刷新由现有通知回读 + CAS 机制驱动；provider 只消费替换后的当前值。
    snapshot.replace(
        systemSettingsWithTool(
            new SystemSettings.Tool(
                Map.of("write", List.of(new PermissionRule("*", PermissionAction.DENY))),
                16,
                5_000L,
                5_000L)));

    assertEquals(PermissionAction.DENY, provider.get().rulesFor("write").getFirst().action());
    assertTrue(provider.get().rulesFor("read").isEmpty());
    verify(repository, times(1)).get();
  }

  /** 转换直接复用快照中已校验的 permission 规则类型，不引入第二套解码/默认。 */
  @Test
  void delegatesToTheSnapshotAggregateWithoutACodec() {
    ToolSettings settings = providerFor(SystemSettingsTestFixture.TOOL).get();

    // SystemSettings.Tool.permission 与 ToolSettings.permission 是同一规则模型；转换后规则完全等价。
    assertEquals(
        SystemSettingsTestFixture.TOOL.permission().get("bash"),
        settings.rulesFor("bash"),
        "permission rules must come from the snapshot aggregate, no second permission source");
  }

  private static ToolSettingsProvider providerFor(SystemSettings.Tool tool) {
    return new SystemSettingsToolSettingsProvider(
        new SystemSettingsSnapshot(systemSettingsWithTool(tool)));
  }

  private static SystemSettings systemSettingsWithTool(SystemSettings.Tool tool) {
    return new SystemSettings(
        tool,
        SystemSettingsTestFixture.AI_RUNTIME,
        SystemSettingsTestFixture.ENVIRONMENT,
        SystemSettingsTestFixture.BACKEND_NETWORK,
        SystemSettingsTestFixture.STORAGE_MEDIA,
        SystemSettingsTestFixture.ADVANCED);
  }
}
