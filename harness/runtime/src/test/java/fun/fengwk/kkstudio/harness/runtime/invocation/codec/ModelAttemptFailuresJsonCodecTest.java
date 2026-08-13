package fun.fengwk.kkstudio.harness.runtime.invocation.codec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelAttemptFailure;
import fun.fengwk.kkstudio.harness.runtime.model.ModelInvocationError;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderErrorKind;

import java.time.Instant;
import java.util.List;

/** Model failed-attempt history 的 deterministic JSON 与严格边界。 */
class ModelAttemptFailuresJsonCodecTest {

  private static final Instant FAILED_AT = Instant.parse("2026-01-01T00:00:00.123Z");
  private static final Instant RETRY_AT = Instant.parse("2026-01-01T00:00:02.123Z");
  private final ModelAttemptFailuresJsonCodec codec = new ModelAttemptFailuresJsonCodec();

  @Test
  void roundTripsExactCanonicalJsonAndReturnsImmutableList() {
    ModelAttemptFailure failure =
        new ModelAttemptFailure(
            1,
            7,
            "partial",
            "thinking",
            new ModelInvocationError(ProviderErrorKind.TRANSIENT, "provider unavailable"),
            FAILED_AT,
            RETRY_AT);

    String json = codec.encode(List.of(failure));

    assertEquals(
        "[{\"attempt\":1,\"sequence\":7,\"text\":\"partial\",\"thinking\":\"thinking\","
            + "\"error\":{\"kind\":\"TRANSIENT\",\"message\":\"provider unavailable\"},"
            + "\"failedAt\":\"2026-01-01T00:00:00.123Z\","
            + "\"retryAt\":\"2026-01-01T00:00:02.123Z\"}]",
        json);
    List<ModelAttemptFailure> decoded = codec.decode(json);
    assertEquals(List.of(failure), decoded);
    assertEquals(List.of(failure), codec.decodeNode(codec.encodeNode(List.of(failure))));
    assertThrows(UnsupportedOperationException.class, () -> decoded.add(failure));
  }

  @Test
  void rejectsMalformedShapeAndInvalidDomainFacts() {
    String base =
        "[{\"attempt\":1,\"sequence\":7,\"text\":\"partial\",\"thinking\":\"\","
            + "\"error\":{\"kind\":\"TRANSIENT\",\"message\":\"down\"},"
            + "\"failedAt\":\"2026-01-01T00:00:00.123Z\","
            + "\"retryAt\":\"2026-01-01T00:00:02.123Z\"}]";

    assertThrows(NullPointerException.class, () -> codec.encode(null));
    assertThrows(IllegalArgumentException.class, () -> codec.decode("{}"));
    assertThrows(
        IllegalArgumentException.class,
        () -> codec.decode(base.replace("\"retryAt\"", "\"extra\":1,\"retryAt\"")));
    assertThrows(
        IllegalArgumentException.class,
        () -> codec.decode(base.replace("\"attempt\":1", "\"attempt\":0")));
    assertThrows(
        IllegalArgumentException.class,
        () -> codec.decode(base.replace("\"sequence\":7", "\"sequence\":-1")));
    assertThrows(
        IllegalArgumentException.class,
        () -> codec.decode(base.replace("\"TRANSIENT\"", "\"AUTHENTICATION\"")));
    assertThrows(
        IllegalArgumentException.class,
        () -> codec.decode(base.replace("2026-01-01T00:00:02.123Z", "not-an-instant")));
    assertThrows(
        IllegalArgumentException.class,
        () -> codec.decode(base.replace("2026-01-01T00:00:02.123Z", "2025-12-31T23:59:59.123Z")));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            codec.decode(base.replace("2026-01-01T00:00:00.123Z", "2026-01-01T00:00:00.123456Z")));
  }
}
