package fun.fengwk.kkstudio.plugin.minimaxmavis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;
import java.util.Set;

/**
 * renewal 请求参数与签名的逐字节一致性。
 *
 * <p>下面的 URL、{@code yy}、{@code x-signature} 期望值由参考桌面实现（MiniMax Code 3.0.49 观测到的请求拦截器逻辑）在固定
 * 时刻、固定时区、固定 device id 下生成，因此这些断言同时锁定参数顺序、{@code URLSearchParams} 编码、时间偏移（含夏令时） 与摘要算法。
 */
class MavisRenewalRequestTest {

  private static final Instant NOW = Instant.ofEpochMilli(1_700_000_000_000L);
  private static final String DEVICE_ID = "12345678";
  private static final String OS_NAME = "Linux";
  private static final String TOKEN = "aaa.bbb.ccc";

  private static MavisCredential credential(MavisRegion region) {
    return new MavisCredential(
        TOKEN,
        region,
        Instant.ofEpochSecond(1_800_000_000L),
        "11111111-2222-3333-4444-555555555555");
  }

  /** CN + 东八区：完整 query、yy 与签名与参考实现一致。 */
  @Test
  void matchesReferenceRequestForCn() {
    MavisRenewalRequest renewal =
        MavisRenewalRequest.sign(
            credential(MavisRegion.CN), NOW, ZoneId.of("Asia/Shanghai"), DEVICE_ID, OS_NAME);

    assertEquals(
        "https://agent.minimaxi.com/v1/api/user/renewal?device_platform=web&biz_id=3&app_id=3001"
            + "&version_code=22201&unix=1700000000000&timezone_offset=28800&is_desktop=1"
            + "&desktop_version=&sys_language=zh&lang=zh"
            + "&uuid=11111111-2222-3333-4444-555555555555&device_id=12345678&os_name=Linux"
            + "&browser_name=unknown&user_id=0&token="
            + TOKEN
            + "&client=desktop",
        renewal.url());
    assertEquals("d4f93266daa438412599c9682cd1dba9", renewal.headers().get("yy"));
    assertEquals("1700000000", renewal.headers().get("x-timestamp"));
    assertEquals("05064c6d0266350eeac121b673536c14", renewal.headers().get("x-signature"));
  }

  /** EN region 只改变语言参数与 origin，签名随之改变。 */
  @Test
  void matchesReferenceRequestForEn() {
    MavisRenewalRequest renewal =
        MavisRenewalRequest.sign(
            credential(MavisRegion.EN), NOW, ZoneId.of("Asia/Shanghai"), DEVICE_ID, OS_NAME);

    assertTrue(renewal.url().startsWith("https://agent.minimax.io/v1/api/user/renewal?"));
    assertTrue(renewal.url().contains("&sys_language=en&lang=en&"));
    assertEquals("c2658e3b06028901e04e9a44dea038ba", renewal.headers().get("yy"));
    assertEquals("05064c6d0266350eeac121b673536c14", renewal.headers().get("x-signature"));
  }

  /** 时间偏移取请求时刻的本地偏移：纽约在同一时刻是 -18000 秒（标准时），签名因此不同。 */
  @Test
  void usesLocalOffsetAtRequestInstant() {
    MavisRenewalRequest shanghai =
        MavisRenewalRequest.sign(
            credential(MavisRegion.CN), NOW, ZoneId.of("Asia/Shanghai"), DEVICE_ID, OS_NAME);
    MavisRenewalRequest newYork =
        MavisRenewalRequest.sign(
            credential(MavisRegion.CN), NOW, ZoneId.of("America/New_York"), DEVICE_ID, OS_NAME);

    assertTrue(shanghai.url().contains("timezone_offset=28800"));
    assertTrue(newYork.url().contains("timezone_offset=-18000"));
    assertEquals("4c794c04bd8a68e7b9b386b04a899d8f", newYork.headers().get("yy"));
  }

  /** 含空格与保留字符的 token / uuid 按参考编码逐字节一致。 */
  @Test
  void encodesReservedCharactersLikeReference() {
    MavisCredential credential =
        new MavisCredential(
            "T+o/k?en=1&2%3 ~x'y(z)!w",
            MavisRegion.CN, Instant.ofEpochSecond(1_800_000_000L), "u+1/2=3 4");
    MavisRenewalRequest renewal =
        MavisRenewalRequest.sign(
            credential,
            Instant.ofEpochMilli(1_700_000_000_999L),
            ZoneId.of("UTC"),
            "87654321",
            OS_NAME);

    assertTrue(renewal.url().contains("&uuid=u%2B1%2F2%3D3+4&"));
    assertTrue(renewal.url().contains("&token=T%2Bo%2Fk%3Fen%3D1%262%253+%7Ex%27y%28z%29%21w&"));
    assertEquals("5fdff36c6475baae56f024a612d20e1c", renewal.headers().get("yy"));
    assertEquals("1700000000", renewal.headers().get("x-timestamp"));
  }

  /** 请求只使用桌面私有 header 集合，且 toString 不泄露 token。 */
  @Test
  void sendsOnlyDesktopHeaders() {
    MavisRenewalRequest renewal =
        MavisRenewalRequest.sign(
            credential(MavisRegion.CN), NOW, ZoneId.of("Asia/Shanghai"), DEVICE_ID, OS_NAME);

    assertEquals(
        Set.of("Content-Type", "User-Agent", "token", "yy", "x-timestamp", "x-signature"),
        renewal.headers().keySet());
    assertEquals("application/json", renewal.headers().get("Content-Type"));
    assertEquals("MiniMaxAgent", renewal.headers().get("User-Agent"));
    assertFalse(renewal.toString().contains(TOKEN));
    assertEquals(
        "MavisRenewalRequest[url=https://agent.minimaxi.com/v1/api/user/renewal]",
        renewal.toString());
  }

  /** 桌面身份只接受显式或会话级取值，操作系统名收敛为四种稳定取值。 */
  @Test
  void desktopIdentityIsExplicitOrSessionScoped() {
    MavisDesktopEnvironment session = MavisDesktopEnvironment.session();

    assertTrue(session.deviceId().matches("[1-9][0-9]{7}"));
    assertEquals(MavisDesktopEnvironment.session().osName(), session.osName());
    assertEquals("Windows", MavisDesktopEnvironment.detectOsName("Windows 11"));
    assertEquals("macOS", MavisDesktopEnvironment.detectOsName("Mac OS X"));
    assertEquals("Linux", MavisDesktopEnvironment.detectOsName("linux"));
    assertEquals("unknown", MavisDesktopEnvironment.detectOsName("FreeBSD"));
  }
}
