package fun.fengwk.kkstudio.platform.pluginhost.credential;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.platform.plugin.PluginCredentialSnapshot;
import fun.fengwk.kkstudio.platform.plugin.PluginCredentialUnavailableException;
import fun.fengwk.kkstudio.platform.plugin.PluginCredentialUnavailableReason;
import fun.fengwk.kkstudio.platform.plugin.credential.PluginCredentialStore;

/** 单 Plugin 访问视图：作用域视图只读写自己的凭据，候选视图绝不触碰真实凭据。 */
class PluginCredentialAccessTest {

  private static final PluginCredentialSnapshot SNAPSHOT =
      new PluginCredentialSnapshot("alpha", null, null, 1L, "{\"key\":\"k\"}");

  private final PluginCredentialStore store = mock(PluginCredentialStore.class);

  /** 作用域视图按自己的 pluginId 读取并委托拒绝。 */
  @Test
  void scopedAccessDelegatesForItsOwnPlugin() {
    ScopedPluginCredentialAccess access = new ScopedPluginCredentialAccess("alpha", store);
    when(store.resolve("alpha")).thenReturn(SNAPSHOT);
    when(store.rejectUsed(SNAPSHOT, "bad")).thenReturn(true);

    assertSame(SNAPSHOT, access.resolve());
    assertTrue(access.rejectUsed(SNAPSHOT, "bad"));
  }

  /** 别的 Plugin 的快照在写入前被拒绝。 */
  @Test
  void scopedAccessRejectsAnotherPluginsSnapshot() {
    ScopedPluginCredentialAccess access = new ScopedPluginCredentialAccess("beta", store);

    assertThrows(IllegalArgumentException.class, () -> access.rejectUsed(SNAPSHOT, "bad"));
    verifyNoInteractions(store);
  }

  /** 候选视图返回将要写入的凭据；没有候选凭据时以 NOT_CONNECTED 拒绝，拒绝调用恒为 no-op。 */
  @Test
  void fixedAccessExposesOnlyTheCandidate() {
    assertSame(SNAPSHOT, new FixedPluginCredentialAccess(SNAPSHOT).resolve());
    assertFalse(new FixedPluginCredentialAccess(SNAPSHOT).rejectUsed(SNAPSHOT, "bad"));

    PluginCredentialUnavailableException error =
        assertThrows(
            PluginCredentialUnavailableException.class,
            () -> new FixedPluginCredentialAccess(null).resolve());
    assertEquals(PluginCredentialUnavailableReason.NOT_CONNECTED, error.reason());
  }
}
