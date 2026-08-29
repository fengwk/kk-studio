package fun.fengwk.kkstudio.harness.environment.daemon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityIds;

import java.time.Duration;

/** Daemon capability INVOKE payload 的 canonical 编解码和严格拒绝边界测试。 */
class DaemonCapabilityInvokeCodecTest {

  private static final String VALID =
      "{\"capabilityId\":\"fs.read\",\"capabilityVersion\":\"1\",\"workspacePath\":\"src/main\","
          + "\"arguments\":{\"path\":\"README.md\"},\"timeoutMillis\":0}";

  private final DaemonCapabilityInvokeCodec codec = new DaemonCapabilityInvokeCodec();

  /** canonical encode 必须固定字段顺序、嵌套 arguments object 且总是带 timeoutMillis。 */
  @Test
  void encodesAllFieldsAndRoundTrips() {
    DaemonCapabilityInvokeCodec.InvokeRequest request =
        new DaemonCapabilityInvokeCodec.InvokeRequest(
            EnvironmentCapabilityIds.FS_READ,
            "1",
            "src/main",
            "{ \"path\": \"README.md\" }",
            Duration.ZERO);

    assertEquals(VALID, codec.encode(request));
    assertEquals(request, codec.decode(VALID));
  }

  /** long 最大值和非 canonical workspace 都是 codec 可表达的合法边界；路径业务校验留给 Daemon。 */
  @Test
  void acceptsLongTimeoutAndOnlyRequiresNonBlankWorkspace() {
    DaemonCapabilityInvokeCodec.InvokeRequest request =
        new DaemonCapabilityInvokeCodec.InvokeRequest(
            EnvironmentCapabilityIds.FS_READ,
            "version with spaces",
            "../outside",
            "{}",
            Duration.ofMillis(Long.MAX_VALUE));

    assertEquals(request, codec.decode(codec.encode(request)));
    assertEquals("../outside", request.workspacePath());
    assertEquals(Long.MAX_VALUE, request.timeout().toMillis());
  }

  /** typed record 构造器校验 null/blank、arguments object 和 timeout 基础值。 */
  @Test
  void rejectsInvalidRequestValues() {
    assertThrows(
        NullPointerException.class,
        () -> new DaemonCapabilityInvokeCodec.InvokeRequest(null, "1", ".", "{}", Duration.ZERO));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new DaemonCapabilityInvokeCodec.InvokeRequest(
                EnvironmentCapabilityIds.FS_READ, " ", ".", "{}", Duration.ZERO));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new DaemonCapabilityInvokeCodec.InvokeRequest(
                EnvironmentCapabilityIds.FS_READ, "1", " ", "{}", Duration.ZERO));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new DaemonCapabilityInvokeCodec.InvokeRequest(
                EnvironmentCapabilityIds.FS_READ, "1", ".", "[]", Duration.ZERO));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new DaemonCapabilityInvokeCodec.InvokeRequest(
                EnvironmentCapabilityIds.FS_READ, "1", ".", "{\"x\":1} trailing", Duration.ZERO));
    assertThrows(
        NullPointerException.class,
        () ->
            new DaemonCapabilityInvokeCodec.InvokeRequest(
                EnvironmentCapabilityIds.FS_READ, "1", ".", "{}", null));
  }

  /** timeout 只接受非负的精确整毫秒，且毫秒值必须能表达为 wire long。 */
  @Test
  void rejectsNonMillisecondNegativeAndOverflowTimeouts() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new DaemonCapabilityInvokeCodec.InvokeRequest(
                EnvironmentCapabilityIds.FS_READ, "1", ".", "{}", Duration.ofNanos(1)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new DaemonCapabilityInvokeCodec.InvokeRequest(
                EnvironmentCapabilityIds.FS_READ, "1", ".", "{}", Duration.ofMillis(-1)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new DaemonCapabilityInvokeCodec.InvokeRequest(
                EnvironmentCapabilityIds.FS_READ,
                "1",
                ".",
                "{}",
                Duration.ofSeconds(Long.MAX_VALUE)));
  }

  /** duplicate、trailing、unknown 和 missing 字段必须在 wire 边界拒绝，避免不同解释器分叉。 */
  @Test
  void rejectsDuplicateTrailingUnknownAndMissingFields() {
    assertInvalid(
        "{\"capabilityId\":\"fs.read\",\"capabilityId\":\"fs.read\",\"capabilityVersion\":\"1\","
            + "\"workspacePath\":\".\",\"arguments\":{},\"timeoutMillis\":0}");
    assertInvalid(VALID + " {}");
    assertInvalid(VALID.replace("\"timeoutMillis\":0", "\"timeoutMillis\":0,\"extra\":1"));
    assertInvalid(
        "{\"capabilityVersion\":\"1\",\"workspacePath\":\".\",\"arguments\":{},\"timeoutMillis\":0}");
    assertInvalid(
        "{\"capabilityId\":\"fs.read\",\"workspacePath\":\".\",\"arguments\":{},\"timeoutMillis\":0}");
    assertInvalid(
        "{\"capabilityId\":\"fs.read\",\"capabilityVersion\":\"1\",\"arguments\":{},\"timeoutMillis\":0}");
    assertInvalid(
        "{\"capabilityId\":\"fs.read\",\"capabilityVersion\":\"1\",\"workspacePath\":\".\","
            + "\"timeoutMillis\":0}");
    assertInvalid(
        "{\"capabilityId\":\"fs.read\",\"capabilityVersion\":\"1\",\"workspacePath\":\".\","
            + "\"arguments\":{}}");
  }

  /** 所有固定字段都必须是规定类型；arguments 只能是 object，不能被数组/标量替代。 */
  @Test
  void rejectsWrongFieldTypesAndNonObjectArguments() {
    assertInvalid(VALID.replace("\"fs.read\"", "1"));
    assertInvalid(VALID.replace("\"capabilityVersion\":\"1\"", "\"capabilityVersion\":1"));
    assertInvalid(VALID.replace("\"workspacePath\":\"src/main\"", "\"workspacePath\":1"));
    assertInvalid(VALID.replace("\"arguments\":{\"path\":\"README.md\"}", "\"arguments\":[]"));
    assertInvalid(VALID.replace("\"arguments\":{\"path\":\"README.md\"}", "\"arguments\":\"{}\""));
    assertInvalid(VALID.replace("\"timeoutMillis\":0", "\"timeoutMillis\":\"0\""));
    assertInvalid("[]");
    assertInvalid("null");
  }

  /** ID 必须使用 EnvironmentCapabilityId canonical 语法，版本/workspace 仅接受非空文本。 */
  @Test
  void rejectsInvalidIdsAndBlankText() {
    assertInvalid(VALID.replace("\"fs.read\"", "\"FS.READ\""));
    assertInvalid(VALID.replace("\"fs.read\"", "\"fs_read\""));
    assertInvalid(VALID.replace("\"capabilityVersion\":\"1\"", "\"capabilityVersion\":\" \""));
    assertInvalid(VALID.replace("\"workspacePath\":\"src/main\"", "\"workspacePath\":\"\""));
  }

  /** timeout 必须是非负整毫秒 long，负数、溢出值和 sub-ms 小数都不得静默截断。 */
  @Test
  void rejectsNegativeOverflowAndSubMillisecondTimeouts() {
    assertInvalid(VALID.replace("\"timeoutMillis\":0", "\"timeoutMillis\":-1"));
    assertInvalid(VALID.replace("\"timeoutMillis\":0", "\"timeoutMillis\":9223372036854775808"));
    assertInvalid(VALID.replace("\"timeoutMillis\":0", "\"timeoutMillis\":1.5"));
    assertInvalid(VALID.replace("\"timeoutMillis\":0", "\"timeoutMillis\":1e0"));
  }

  private void assertInvalid(String json) {
    assertThrows(DaemonProtocolException.class, () -> codec.decode(json), json);
  }
}
