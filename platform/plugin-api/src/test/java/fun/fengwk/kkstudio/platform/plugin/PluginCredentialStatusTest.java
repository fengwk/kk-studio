package fun.fengwk.kkstudio.platform.plugin;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.util.Set;

/** PluginCredentialStatus 区分投影态与持久列取值，测试锁定只有权威状态会写入 {@code plugin_credential.status}。 */
class PluginCredentialStatusTest {

  @Test
  void onlyAuthoritativeStatesArePersisted() {
    Set<PluginCredentialStatus> persisted =
        Set.of(
            PluginCredentialStatus.CONNECTED,
            PluginCredentialStatus.REFRESH_FAILED,
            PluginCredentialStatus.REFRESH_UNCERTAIN,
            PluginCredentialStatus.REAUTH_REQUIRED);

    for (PluginCredentialStatus status : PluginCredentialStatus.values()) {
      if (persisted.contains(status)) {
        assertTrue(status.isPersisted(), status + " must be a persisted column value");
      } else {
        assertFalse(status.isPersisted(), status + " must stay a read projection");
      }
    }
  }
}
