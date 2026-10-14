package fun.fengwk.kkstudio.platform.plugin.credential;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.platform.plugin.PluginCredentialSnapshot;
import fun.fengwk.kkstudio.platform.plugin.PluginCredentialUnavailableException;
import fun.fengwk.kkstudio.platform.plugin.PluginCredentialUnavailableReason;

import java.util.UUID;

/** 私有候选凭据视图：只服务本次提交的固定快照，「无凭据」候选必须如实失败关闭且不承担调用期作废。 */
class FixedPluginCredentialAccessTest {

  private static final String PLUGIN_ID = "fixed.plugin";
  private static final UUID GENERATION = UUID.fromString("11111111-1111-1111-1111-111111111111");
  private static final UUID OTHER_GENERATION =
      UUID.fromString("22222222-2222-2222-2222-222222222222");
  private static final String CONTEXT = "a1".repeat(32);
  private static final String OTHER_CONTEXT = "b2".repeat(32);

  @Test
  void resolveReturnsTheFixedCandidateSnapshot() {
    PluginCredentialSnapshot snapshot = snapshot(GENERATION, CONTEXT, "{\"apiKey\":\"candidate\"}");
    FixedPluginCredentialAccess access =
        new FixedPluginCredentialAccess(snapshot.reference(), snapshot);

    assertEquals(snapshot, access.resolve());
    assertFalse(
        access.rejectUsed(snapshot, "candidate instances never invalidate live credentials"));
  }

  /** 本次提交的私有无凭据视图：解析必须失败关闭，绝不回读权威 store 里的旧行。 */
  @Test
  void credentiallessCandidateFailsClosed() {
    FixedPluginCredentialAccess access =
        new FixedPluginCredentialAccess(
            new PluginCredentialReference(PLUGIN_ID, GENERATION, CONTEXT), null);

    PluginCredentialUnavailableException unavailable =
        assertThrows(PluginCredentialUnavailableException.class, access::resolve);
    assertEquals(PluginCredentialUnavailableReason.NOT_CONNECTED, unavailable.reason());
    assertFalse(access.rejectUsed(null, "no credential to reject"));
  }

  @Test
  void rejectsASnapshotBelongingToAnotherReference() {
    PluginCredentialSnapshot snapshot = snapshot(OTHER_GENERATION, CONTEXT, "{\"apiKey\":\"x\"}");

    assertThrows(
        IllegalArgumentException.class,
        () ->
            new FixedPluginCredentialAccess(
                new PluginCredentialReference(PLUGIN_ID, GENERATION, CONTEXT), snapshot));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new FixedPluginCredentialAccess(
                new PluginCredentialReference(PLUGIN_ID, GENERATION, OTHER_CONTEXT), snapshot));
    assertThrows(NullPointerException.class, () -> new FixedPluginCredentialAccess(null, snapshot));
  }

  private static PluginCredentialSnapshot snapshot(
      UUID generation, String context, String payloadJson) {
    return new PluginCredentialSnapshot(
        PLUGIN_ID, generation, context, null, null, 0L, new byte[] {1, 2, 3}, payloadJson);
  }
}
