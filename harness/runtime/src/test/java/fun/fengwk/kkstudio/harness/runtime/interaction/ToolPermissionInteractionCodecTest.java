package fun.fengwk.kkstudio.harness.runtime.interaction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;

/** Strict persisted prompt projection and approval parsing are isolated in the product codec. */
class ToolPermissionInteractionCodecTest {
  private final ToolPermissionInteractionCodec codec =
      new ToolPermissionInteractionCodec(new ObjectMapper());

  @Test
  void projectsSafePreviewAndResolvesBothDecisions() {
    InteractionRequest request = request();

    assertEquals(
        "{\"tool\":\"bash\",\"workdir\":\"/workspace\",\"arguments\":\"{\\\"command\\\":\\\"pwd\\\"}\"}",
        codec.project(request).json());
    assertEquals(
        ToolPermissionDecision.APPROVE,
        codec.resolve(request, new InteractionResponse("{\"approved\":true}")));
    assertEquals(
        ToolPermissionDecision.DENY,
        codec.resolve(request, new InteractionResponse("{\"approved\":false}")));
  }

  @Test
  void rejectsMalformedPayloadsAndMismatchedDurableInvocation() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            codec.project(
                new InteractionRequest(
                    "{\"invocationId\":41,\"threadId\":7,\"tool\":\"bash\",\"workdir\":\"/workspace\"}")));
    assertThrows(
        IllegalArgumentException.class,
        () -> codec.resolve(request(), new InteractionResponse("{\"approved\":true,\"extra\":1}")));
    Interaction mismatched =
        new Interaction(
            1L,
            42L,
            request(),
            InteractionStatus.OPEN,
            null,
            0L,
            Instant.parse("2026-01-01T00:00:00Z"),
            null);
    assertThrows(
        IllegalArgumentException.class,
        () -> codec.resolve(mismatched, new InteractionResponse("{\"approved\":true}")));
  }

  private static InteractionRequest request() {
    return new InteractionRequest(
        """
        {
          "invocationId": 41,
          "threadId": 7,
          "tool": "bash",
          "workdir": "/workspace",
          "arguments": "{\\"command\\":\\"pwd\\"}"
        }
        """);
  }
}
