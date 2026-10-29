package fun.fengwk.kkstudio.platform.plugin.support;

import fun.fengwk.kkstudio.platform.plugin.PluginCredentialSnapshot;
import fun.fengwk.kkstudio.platform.plugin.credential.PluginCredentialAccess;

import java.util.function.Consumer;

/** 作废本次实际使用的快照；次要存储失败不覆盖远端认证拒绝，Error 不吞。 */
public final class CredentialRejection {
  private CredentialRejection() {}

  public static void rejectUsed(
      PluginCredentialAccess access,
      PluginCredentialSnapshot snapshot,
      String message,
      Consumer<RuntimeException> onFailure) {
    try {
      access.rejectUsed(snapshot, message);
    } catch (RuntimeException error) {
      onFailure.accept(error);
    }
  }
}
