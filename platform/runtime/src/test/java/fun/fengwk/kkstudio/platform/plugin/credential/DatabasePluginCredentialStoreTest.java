package fun.fengwk.kkstudio.platform.plugin.credential;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.platform.plugin.PluginCredentialSnapshot;
import fun.fengwk.kkstudio.platform.plugin.PluginCredentialStatus;
import fun.fengwk.kkstudio.platform.plugin.PluginCredentialUnavailableException;
import fun.fengwk.kkstudio.platform.plugin.PluginCredentialUnavailableReason;
import fun.fengwk.kkstudio.platform.plugin.persistence.PluginCredentialRepository;
import fun.fengwk.kkstudio.platform.plugin.persistence.PluginCredentialRow;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

/** {@link DatabasePluginCredentialStore} 按状态、刷新租约与本地时限判定凭据可用性。 */
class DatabasePluginCredentialStoreTest {

  private static final String PLUGIN_ID = "test.plugin";
  private static final Instant NOW = Instant.parse("2026-10-01T12:00:00Z");
  private static final String PAYLOAD = "{\"token\":\"secret\"}";

  private final PluginCredentialRepository repository = mock(PluginCredentialRepository.class);
  private final DatabasePluginCredentialStore store =
      new DatabasePluginCredentialStore(repository, Clock.fixed(NOW, ZoneOffset.UTC));

  /** 没有凭据行时投影为未连接，读取以 NOT_CONNECTED 拒绝。 */
  @Test
  void missingRowIsNotConnected() {
    when(repository.find(PLUGIN_ID)).thenReturn(Optional.empty());

    assertEquals(PluginCredentialStatus.NOT_CONNECTED, store.projection(PLUGIN_ID).status());
    assertReason(PluginCredentialUnavailableReason.NOT_CONNECTED);
  }

  /** CONNECTED 与 REFRESH_FAILED 在时限内都返回带版本的快照；投影不含载荷。 */
  @Test
  void usableStatusesResolveToASnapshot() {
    for (PluginCredentialStatus status :
        new PluginCredentialStatus[] {
          PluginCredentialStatus.CONNECTED, PluginCredentialStatus.REFRESH_FAILED
        }) {
      when(repository.find(PLUGIN_ID))
          .thenReturn(Optional.of(row(status, NOW.plusSeconds(60), null)));

      assertEquals(
          new PluginCredentialSnapshot(PLUGIN_ID, "CN", NOW.plusSeconds(60), 3L, PAYLOAD),
          store.resolve(PLUGIN_ID));
      assertEquals(status, store.projection(PLUGIN_ID).status());
      assertEquals("CN", store.projection(PLUGIN_ID).region());
    }
  }

  /** 静态密钥没有过期时刻，始终可用。 */
  @Test
  void staticKeyWithoutExpiryResolves() {
    when(repository.find(PLUGIN_ID))
        .thenReturn(
            Optional.of(
                new PluginCredentialRow(
                    PLUGIN_ID,
                    PAYLOAD,
                    null,
                    null,
                    null,
                    PluginCredentialStatus.CONNECTED,
                    null,
                    null,
                    null,
                    null,
                    0L)));

    assertEquals(PAYLOAD, store.resolve(PLUGIN_ID).payloadJson());
  }

  /** 刷新在途、需重新认证、结果未知与已过期分别以对应原因拒绝。 */
  @Test
  void unusableRowsAreRejectedWithTheirReason() {
    when(repository.find(PLUGIN_ID))
        .thenReturn(
            Optional.of(
                row(PluginCredentialStatus.CONNECTED, NOW.plusSeconds(60), NOW.plusSeconds(5))));
    assertReason(PluginCredentialUnavailableReason.AUTH_REFRESHING);

    when(repository.find(PLUGIN_ID))
        .thenReturn(Optional.of(row(PluginCredentialStatus.REAUTH_REQUIRED, null, null)));
    assertReason(PluginCredentialUnavailableReason.REAUTH_REQUIRED);

    when(repository.find(PLUGIN_ID))
        .thenReturn(Optional.of(row(PluginCredentialStatus.REFRESH_UNCERTAIN, null, null)));
    assertReason(PluginCredentialUnavailableReason.REFRESH_UNCERTAIN);

    // 过期租约不算刷新在途；到达过期时刻即不可用。
    when(repository.find(PLUGIN_ID))
        .thenReturn(Optional.of(row(PluginCredentialStatus.CONNECTED, NOW, NOW.minusSeconds(1))));
    assertReason(PluginCredentialUnavailableReason.EXPIRED);
  }

  /** 调用期拒绝只作废读取时的那一版，错误摘要有界且空值退化为固定文本。 */
  @Test
  void rejectUsedTargetsTheReadVersion() {
    PluginCredentialSnapshot snapshot =
        new PluginCredentialSnapshot(PLUGIN_ID, "CN", NOW.plusSeconds(60), 3L, PAYLOAD);
    when(repository.rejectUsed(PLUGIN_ID, 3L, "token revoked")).thenReturn(true);

    assertTrue(store.rejectUsed(snapshot, " token revoked\n"));

    store.rejectUsed(snapshot, " ");
    verify(repository).rejectUsed(PLUGIN_ID, 3L, DatabasePluginCredentialStore.AUTH_REJECTED_ERROR);
  }

  private void assertReason(PluginCredentialUnavailableReason reason) {
    PluginCredentialUnavailableException error =
        assertThrows(PluginCredentialUnavailableException.class, () -> store.resolve(PLUGIN_ID));
    assertEquals(reason, error.reason());
  }

  private static PluginCredentialRow row(
      PluginCredentialStatus status, Instant expiresAt, Instant leaseUntil) {
    return new PluginCredentialRow(
        PLUGIN_ID,
        PAYLOAD,
        "CN",
        expiresAt,
        NOW.plusSeconds(30),
        status,
        null,
        null,
        leaseUntil == null ? null : "lease",
        leaseUntil,
        3L);
  }
}
