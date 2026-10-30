package fun.fengwk.kkstudio.harness.runtime.invocation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.common.json.StrictJson;
import fun.fengwk.kkstudio.harness.builtin.model.ModelAttemptFailure;
import fun.fengwk.kkstudio.harness.builtin.model.StreamCheckpoint;
import fun.fengwk.kkstudio.harness.model.ModelInvocationError;
import fun.fengwk.kkstudio.harness.model.provider.ProviderErrorKind;
import fun.fengwk.kkstudio.harness.runtime.entry.ModelSelection;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInputReceipt;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolInvocationError;
import fun.fengwk.kkstudio.harness.tool.ToolCall;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 直接严格绑定的 Invocation 持久列：锁定 wire 形态（字段名、顺序与时间格式），并确认非法值由 record 构造边界拒绝。
 *
 * <p>通用的严格性（未知、缺失、重复字段与尾随内容）由 {@code StrictJsonTest} 覆盖，这里只断言各类型自身的形态与不变量。
 */
class InvocationValueJsonTest {

  private static final StrictJson JSON = StrictJson.standard();

  @Test
  void toolCallKeepsArgumentsAsJsonObjectText() {
    ToolCall call = new ToolCall("call-1", "bash", "{\"command\":\"ls\"}");
    String json =
        "{\"id\":\"call-1\",\"toolName\":\"bash\",\"argumentsJson\":\"{\\\"command\\\":\\\"ls\\\"}\"}";

    assertEquals(json, JSON.write(call, "toolCall"));
    assertEquals(call, JSON.read(json, ToolCall.class, "toolCall"));
    assertRejected(
        "{\"id\":\"call-1\",\"toolName\":\"bash\",\"argumentsJson\":\"not-json\"}", ToolCall.class);
  }

  @Test
  void streamCheckpointRequiresSomeTextOrThinking() {
    StreamCheckpoint checkpoint = new StreamCheckpoint(2, 7, "text", "thinking");
    String json = "{\"attempt\":2,\"sequence\":7,\"text\":\"text\",\"thinking\":\"thinking\"}";

    assertEquals(json, JSON.write(checkpoint, "streamCheckpoint"));
    assertEquals(checkpoint, JSON.read(json, StreamCheckpoint.class, "streamCheckpoint"));
    assertRejected(
        "{\"attempt\":0,\"sequence\":0,\"text\":\"x\",\"thinking\":\"\"}", StreamCheckpoint.class);
    assertRejected(
        "{\"attempt\":1,\"sequence\":0,\"text\":\"\",\"thinking\":\"\"}", StreamCheckpoint.class);
  }

  @Test
  void toolInputReceiptRequiresCanonicalActor() {
    ToolInputReceipt receipt =
        new ToolInputReceipt(
            UUID.fromString("11111111-1111-1111-1111-111111111111"),
            "alice",
            Instant.parse("2026-09-27T00:00:00Z"));
    String json =
        "{\"submissionId\":\"11111111-1111-1111-1111-111111111111\",\"actor\":\"alice\","
            + "\"acceptedAt\":\"2026-09-27T00:00:00Z\"}";

    assertEquals(json, JSON.write(receipt, "toolInputReceipt"));
    assertEquals(receipt, JSON.read(json, ToolInputReceipt.class, "toolInputReceipt"));
    assertRejected(json.replace("\"alice\"", "\"  alice  \""), ToolInputReceipt.class);
    assertRejected(
        json.replace("11111111-1111-1111-1111-111111111111", "not-a-uuid"), ToolInputReceipt.class);
  }

  @Test
  void toolInvocationErrorCarriesKindAndMessage() {
    ToolInvocationError error = new ToolInvocationError("PERMISSION_DENIED", "Rejected.");
    String json = "{\"kind\":\"PERMISSION_DENIED\",\"message\":\"Rejected.\"}";

    assertEquals(json, JSON.write(error, "toolInvocationError"));
    assertEquals(error, JSON.read(json, ToolInvocationError.class, "toolInvocationError"));
  }

  @Test
  void modelInvocationErrorMatchesCanonicalFixtureAndValidatesStatus() {
    ModelInvocationError error =
        new ModelInvocationError(ProviderErrorKind.TRANSIENT, "provider temporarily unavailable");
    String canonical =
        JSON.write(
            JSON.read(
                resource(
                    "/fun/fengwk/kkstudio/harness/runtime/invocation/model-invocation-error.json"),
                ModelInvocationError.class,
                "modelInvocationError"),
            "modelInvocationError");

    assertEquals(canonical, JSON.write(error, "modelInvocationError"));
    for (ProviderErrorKind kind : ProviderErrorKind.values()) {
      ModelInvocationError value = new ModelInvocationError(kind, "message", 503);
      assertEquals(value, roundTrip(value, ModelInvocationError.class));
    }
    assertRejected(canonical.replace("null", "600"), ModelInvocationError.class);
    assertRejected(canonical.replace("TRANSIENT", "NETWORK"), ModelInvocationError.class);
    assertRejected(
        canonical.replace("provider temporarily unavailable", " "), ModelInvocationError.class);
  }

  @Test
  void attemptFailuresKeepMillisecondInstantsAndRejectDedicatedTerminalErrors() {
    ModelAttemptFailure failure =
        new ModelAttemptFailure(
            1,
            7,
            "partial",
            "thinking",
            ModelSelection.model("model", "v1"),
            null,
            new ModelInvocationError(ProviderErrorKind.TRANSIENT, "provider unavailable"),
            Instant.parse("2026-01-01T00:00:00.123Z"),
            Instant.parse("2026-01-01T00:00:02.123Z"));
    String json =
        "[{\"attempt\":1,\"sequence\":7,\"text\":\"partial\",\"thinking\":\"thinking\","
            + "\"model\":{\"modelName\":\"model\",\"variant\":\"v1\"},\"requestedAt\":null,"
            + "\"error\":{\"kind\":\"TRANSIENT\",\"message\":\"provider unavailable\","
            + "\"httpStatus\":null},"
            + "\"failedAt\":\"2026-01-01T00:00:00.123Z\","
            + "\"retryAt\":\"2026-01-01T00:00:02.123Z\"}]";

    assertEquals(json, JSON.write(List.of(failure), "modelAttemptFailures"));
    assertEquals(
        List.of(failure), JSON.readList(json, ModelAttemptFailure.class, "modelAttemptFailures"));
    for (String invalid :
        new String[] {
          json.replace("\"TRANSIENT\"", "\"OVERFLOW\""),
          json.replace("\"TRANSIENT\"", "\"CANCELLED\""),
          json.replace("2026-01-01T00:00:02.123Z", "2025-12-31T23:59:59.123Z"),
          json.replace("2026-01-01T00:00:00.123Z", "2026-01-01T00:00:00.123456Z")
        }) {
      assertThrows(
          IllegalArgumentException.class,
          () -> JSON.readList(invalid, ModelAttemptFailure.class, "modelAttemptFailures"),
          invalid);
    }
  }

  private static <T> T roundTrip(T value, Class<T> type) {
    return JSON.read(JSON.write(value, "value"), type, "value");
  }

  private static void assertRejected(String json, Class<?> type) {
    assertThrows(IllegalArgumentException.class, () -> JSON.read(json, type, "value"), json);
  }

  private static String resource(String name) {
    try (InputStream input =
        Objects.requireNonNull(InvocationValueJsonTest.class.getResourceAsStream(name), name)) {
      return new String(input.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException error) {
      throw new UncheckedIOException(error);
    }
  }
}
