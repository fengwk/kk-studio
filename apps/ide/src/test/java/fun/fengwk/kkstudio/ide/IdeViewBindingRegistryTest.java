package fun.fengwk.kkstudio.ide;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.platform.daemon.server.DaemonConnectionIdentity;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 意图：路由绑定的写入与替换在登记锁内复核当前 READY 实例，迟到的旧实例结果不能删除新代际的绑定。 */
class IdeViewBindingRegistryTest {

  private static final EnvironmentId ENV =
      EnvironmentId.parse("11111111-1111-1111-1111-111111111111");
  private static final UUID VIEW = UUID.fromString("11111111-2222-3333-4444-555555555555");
  private static final UUID DOCUMENT = UUID.fromString("66666666-7777-8888-9999-aaaaaaaaaaaa");
  private static final UUID EVENT_NODE = UUID.fromString("bbbbbbbb-cccc-dddd-eeee-ffffffffffff");
  private static final UUID CONNECTION = UUID.fromString("12345678-1234-1234-1234-123456789abc");
  private static final UUID CLAIM = UUID.fromString("87654321-4321-4321-4321-cba987654321");
  private static final DaemonConnectionIdentity REPLACED =
      new DaemonConnectionIdentity(ENV, "daemon-1");
  private static final DaemonConnectionIdentity CURRENT =
      new DaemonConnectionIdentity(ENV, "daemon-2");

  @Test
  void lateGenerationCannotRecordOrReplaceCurrentBindings() {
    IdeViewBindingRegistry registry =
        new IdeViewBindingRegistry(environmentId -> Optional.of(CURRENT));
    assertTrue(registry.record(binding(CURRENT, 1L)));

    // 旧实例迟到的登记与权威快照都在登记锁内被拒绝，不得清理当前实例的绑定。
    assertFalse(registry.record(binding(REPLACED, 1L)), "旧实例的登记必须被锁内复核拒绝");
    assertFalse(registry.replace(REPLACED, List.of()), "旧实例的快照不得删除当前实例绑定");
    assertEquals(List.of(binding(CURRENT, 1L)), registry.list(CURRENT));
    assertTrue(registry.find(REPLACED, VIEW).isEmpty());

    assertTrue(registry.replace(CURRENT, List.of()));
    assertTrue(registry.find(CURRENT, VIEW).isEmpty());
  }

  /** 当前实例自身的同 view 换代仍按 epoch 精确替换，旧 epoch 不残留。 */
  @Test
  void currentGenerationReplacesItsOwnSnapshot() {
    IdeViewBindingRegistry registry =
        new IdeViewBindingRegistry(environmentId -> Optional.of(CURRENT));
    assertTrue(registry.record(binding(CURRENT, 1L)));
    assertTrue(registry.record(binding(CURRENT, 2L)));
    assertEquals(2L, registry.find(CURRENT, VIEW).orElseThrow().bindingEpoch());

    assertTrue(registry.replace(CURRENT, List.of(binding(CURRENT, 2L))));
    assertEquals(1, registry.list(CURRENT).size());
  }

  private static IdeViewBindingRegistry.Binding binding(
      DaemonConnectionIdentity identity, long bindingEpoch) {
    return new IdeViewBindingRegistry.Binding(
        identity, VIEW, DOCUMENT, EVENT_NODE, CONNECTION, CLAIM, bindingEpoch);
  }
}
