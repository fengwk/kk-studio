package fun.fengwk.kkstudio.share.notification;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

/**
 * Verifies the shared browser/Node interop fixture against the real Java carrier: every fixture
 * frame must be exactly what the Java encoder produces from the fixture payload, and decoding and
 * reassembling the fixture frames must reproduce that payload. The fixture therefore stays a
 * genuine cross-language artifact instead of two hand-written, mutually unverified formats.
 *
 * <p>The fixture is shared verbatim with the Vitest suite in {@code
 * frontend/src/shared/notification/__tests__}.
 */
class NotificationInteropFixtureTest {
  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Test
  void javaEncodingAndDecodingMatchTheSharedFixture() throws IOException {
    JsonNode root = load();
    NotificationLimits limits = NotificationLimits.defaults();
    UUID self = UUID.fromString(root.get("self").asText());

    for (JsonNode node : root.get("roundTrips")) {
      verifyRoundTrip(node, limits, self);
    }
    for (JsonNode node : root.get("rejections")) {
      assertThrows(
          IllegalArgumentException.class,
          () -> NotificationCarrier.decode(node.get("frame").asText(), limits, self),
          node.get("name").asText());
    }
    for (JsonNode node : root.get("ownEchoes")) {
      assertNull(
          NotificationCarrier.decode(
              node.get("frame").asText(), limits, UUID.fromString(node.get("self").asText())),
          node.get("name").asText());
    }
  }

  private static void verifyRoundTrip(JsonNode node, NotificationLimits limits, UUID self) {
    String name = node.get("name").asText();
    byte[] payload = Base64.getDecoder().decode(node.get("payloadBase64").asText());
    UUID publisher = UUID.fromString(node.get("publisher").asText());
    UUID target = node.get("target").isNull() ? null : UUID.fromString(node.get("target").asText());
    UUID messageId = UUID.fromString(node.get("messageId").asText());
    String topic = node.get("topic").asText();
    NotificationPacket packet =
        new NotificationPacket(publisher, target, topic, messageId, payload);

    JsonNode frames = node.get("frames");
    assertEquals(NotificationCarrier.count(payload.length), frames.size(), name);
    for (int index = 0; index < frames.size(); index++) {
      assertEquals(
          frames.get(index).asText(), NotificationCarrier.chunk(packet, index).encode(), name);
    }

    List<NotificationPacket> delivered = new ArrayList<>();
    List<String> resyncs = new ArrayList<>();
    NotificationReassembler reassembler =
        new NotificationReassembler(self, limits, System::nanoTime, delivered::add, resyncs::add);
    for (JsonNode frame : frames) {
      reassembler.accept(NotificationCarrier.decode(frame.asText(), limits, self));
    }
    assertEquals(1, delivered.size(), name);
    assertArrayEquals(payload, delivered.getFirst().bytes(), name);
    assertEquals(messageId, delivered.getFirst().messageId(), name);
    assertEquals(topic, delivered.getFirst().topic(), name);
    assertEquals(target, delivered.getFirst().target(), name);
    assertTrue(resyncs.isEmpty(), name);
  }

  private static JsonNode load() throws IOException {
    try (InputStream input =
        NotificationInteropFixtureTest.class.getResourceAsStream("notification-interop.json")) {
      assertNotNull(input, "shared interop fixture must be on the test classpath");
      return MAPPER.readTree(input);
    }
  }
}
