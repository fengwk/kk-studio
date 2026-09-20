package fun.fengwk.kkstudio.plugin.minimaxmavis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import fun.fengwk.kkstudio.platform.plugin.PluginAuthRejectedException;
import fun.fengwk.kkstudio.platform.plugin.PluginCredentialException;
import fun.fengwk.kkstudio.platform.plugin.PluginCredentialMaterial;
import fun.fengwk.kkstudio.platform.plugin.PluginCredentialSnapshot;
import fun.fengwk.kkstudio.platform.plugin.PluginRenewalNotSentException;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

/**
 * MiniMax Mavis 凭据自动刷新器（MiniMaxMavisCredentialRefresher）测试。
 *
 * <p>验证刷新成功时新凭据材料的 region 与快照严格一致； 验证 HTTP 401 与服务端认证错误码收敛为
 * PluginAuthRejectedException（REAUTH_REQUIRED）； 验证本地签名阶段校验失败收敛为
 * PluginRenewalNotSentException（证明未触碰网络）； 验证传输失败、超时与无法解析响应收敛为普通
 * PluginCredentialException（结果未知，永不重放）。
 */
class MiniMaxMavisCredentialRefresherTest {

  private static final Instant FIXED_NOW = Instant.ofEpochSecond(1_700_000_000L);
  private static final Clock CLOCK = Clock.fixed(FIXED_NOW, ZoneOffset.UTC);
  private static final String OLD_TOKEN = MavisTestTokens.withExp(1_700_000_000L + 3600L);
  private static final String CLIENT_UUID = "11111111-2222-3333-4444-555555555555";

  private PluginCredentialSnapshot snapshotCn;

  @BeforeEach
  void setUp() {
    String payloadJson =
        new MiniMaxMavisCredentialPayload(OLD_TOKEN, CLIENT_UUID, FIXED_NOW).toJson();
    this.snapshotCn =
        new PluginCredentialSnapshot(
            MiniMaxMavisPlugin.PLUGIN_ID, "CN", FIXED_NOW.plusSeconds(3600), payloadJson);
  }

  /** 验证刷新成功时，新材料的 region 与快照严格一致，且携带新 token 与更新的刷新计划。 */
  @Test
  void refreshSuccessPreservesRegionAndUpdatesMaterial() {
    long newExp = FIXED_NOW.getEpochSecond() + 86400L * 15L;
    String newToken = MavisTestTokens.withExp(newExp);
    MavisCredential renewed =
        new MavisCredential(newToken, MavisRegion.CN, Instant.ofEpochSecond(newExp), CLIENT_UUID);

    MavisClient mockClient = mock(MavisClient.class);
    when(mockClient.renew(any())).thenReturn(renewed);

    MiniMaxMavisCredentialRefresher refresher =
        new MiniMaxMavisCredentialRefresher(mockClient, CLOCK);
    PluginCredentialMaterial material = refresher.refresh(snapshotCn);

    assertNotNull(material);
    assertEquals("CN", material.region(), "Material region must match snapshot region");
    assertEquals(Instant.ofEpochSecond(newExp), material.expiresAt());

    MiniMaxMavisCredentialPayload payload =
        MiniMaxMavisCredentialPayload.parse(material.payloadJson());
    assertEquals(newToken, payload.accessToken(), "Access token in payload must be updated");
    assertEquals(CLIENT_UUID, payload.clientUuid(), "Client UUID must be preserved");
    assertEquals(
        FIXED_NOW, payload.obtainedAt(), "Obtained time must be updated to current instant");
  }

  /** 验证服务端确定性认证拒绝（HTTP 401 或业务认证码）转换为 PluginAuthRejectedException。 */
  @ParameterizedTest
  @ValueSource(strings = {"HTTP 401 unauthorized", "Mavis authentication rejected (1004)"})
  void authRejectionThrowsPluginAuthRejectedException(String rejectionMessage) {
    MavisClient mockClient = mock(MavisClient.class);
    when(mockClient.renew(any())).thenThrow(new MavisAuthException(rejectionMessage));

    MiniMaxMavisCredentialRefresher refresher =
        new MiniMaxMavisCredentialRefresher(mockClient, CLOCK);

    PluginAuthRejectedException error =
        assertThrows(
            PluginAuthRejectedException.class,
            () -> refresher.refresh(snapshotCn),
            "Server auth rejection must map to PluginAuthRejectedException");

    assertTrue(
        error.getMessage().contains(rejectionMessage)
            || error.getCause() instanceof MavisAuthException);
  }

  /** 验证本地签名阶段失败（在未发送网络请求前发生）转换为 PluginRenewalNotSentException。 */
  @Test
  void localValidationFailureThrowsPluginRenewalNotSentException() {
    MavisClient mockClient = mock(MavisClient.class);
    when(mockClient.renew(any()))
        .thenThrow(new MavisValidationException("renewal signature failed: invalid device id"));

    MiniMaxMavisCredentialRefresher refresher =
        new MiniMaxMavisCredentialRefresher(mockClient, CLOCK);

    PluginRenewalNotSentException error =
        assertThrows(
            PluginRenewalNotSentException.class,
            () -> refresher.refresh(snapshotCn),
            "Local validation error before transport must map to PluginRenewalNotSentException");

    assertInstanceOf(MavisValidationException.class, error.getCause());
  }

  /** 验证传输失败或超时转换为普通 PluginCredentialException（结果未知，不可重放）。 */
  @Test
  void transportFailureThrowsOrdinaryPluginCredentialException() {
    MavisClient mockClient = mock(MavisClient.class);
    when(mockClient.renew(any()))
        .thenThrow(new MavisTransportException("network connection reset during renewal"));

    MiniMaxMavisCredentialRefresher refresher =
        new MiniMaxMavisCredentialRefresher(mockClient, CLOCK);

    PluginCredentialException error =
        assertThrows(
            PluginCredentialException.class,
            () -> refresher.refresh(snapshotCn),
            "Transport failure must map to PluginCredentialException");

    assertFalse(
        error instanceof PluginAuthRejectedException,
        "Unknown outcome must not be treated as PluginAuthRejectedException");
    assertFalse(
        error instanceof PluginRenewalNotSentException,
        "Unknown outcome must not be treated as PluginRenewalNotSentException");
    assertTrue(error.getMessage().contains("unknown"));
  }

  /** 验证响应格式错误或协议异常转换为普通 PluginCredentialException。 */
  @Test
  void protocolFailureThrowsOrdinaryPluginCredentialException() {
    MavisClient mockClient = mock(MavisClient.class);
    when(mockClient.renew(any()))
        .thenThrow(new MavisProtocolException("renewal response missing data.token"));

    MiniMaxMavisCredentialRefresher refresher =
        new MiniMaxMavisCredentialRefresher(mockClient, CLOCK);

    PluginCredentialException error =
        assertThrows(PluginCredentialException.class, () -> refresher.refresh(snapshotCn));

    assertFalse(error instanceof PluginAuthRejectedException);
    assertFalse(error instanceof PluginRenewalNotSentException);
    assertTrue(error.getMessage().contains("unknown"));
  }

  /** 验证快照携带未知 region 标识时抛出 PluginCredentialException。 */
  @Test
  void unrecognizedRegionInSnapshotThrowsPluginCredentialException() {
    PluginCredentialSnapshot badRegionSnapshot =
        new PluginCredentialSnapshot(
            MiniMaxMavisPlugin.PLUGIN_ID,
            "UNKNOWN_REGION",
            FIXED_NOW.plusSeconds(3600),
            snapshotCn.payloadJson());

    MavisClient mockClient = mock(MavisClient.class);
    MiniMaxMavisCredentialRefresher refresher =
        new MiniMaxMavisCredentialRefresher(mockClient, CLOCK);

    assertThrows(
        PluginCredentialException.class,
        () -> refresher.refresh(badRegionSnapshot),
        "Unrecognised region in snapshot must fail fast");
  }

  /** 验证如果 renewal 变更了 region，必须抛出 PluginCredentialException 拒绝。 */
  @Test
  void renewalChangingRegionIsRejected() {
    // snapshot 是 CN，但 renewal 返回 EN
    long newExp = FIXED_NOW.getEpochSecond() + 86400L * 15L;
    String newToken = MavisTestTokens.withExp(newExp);
    MavisCredential renewedEn =
        new MavisCredential(newToken, MavisRegion.EN, Instant.ofEpochSecond(newExp), CLIENT_UUID);

    MavisClient mockClient = mock(MavisClient.class);
    when(mockClient.renew(any())).thenReturn(renewedEn);

    MiniMaxMavisCredentialRefresher refresher =
        new MiniMaxMavisCredentialRefresher(mockClient, CLOCK);

    PluginCredentialException error =
        assertThrows(
            PluginCredentialException.class,
            () -> refresher.refresh(snapshotCn),
            "Renewal changing region must be rejected");

    assertTrue(error.getMessage().contains("changed the credential region"));
  }
}
