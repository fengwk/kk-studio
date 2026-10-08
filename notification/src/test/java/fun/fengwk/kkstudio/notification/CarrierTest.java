package fun.fengwk.kkstudio.notification;

import static fun.fengwk.kkstudio.notification.NotificationTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;

class CarrierTest {
  private final UUID self = UUID.randomUUID();
  private final NotificationLimits limits = NotificationLimits.defaults();

  @Test
  void emptySmallAndOversizeUnicodeAllUseSameBoundedCarrier() {
    for (String payload : new String[] {"", "hint", "你好😀|\n".repeat(30000)}) {
      WireMessage message = wire(UUID.randomUUID(), self, payload);
      byte[] reconstructed = new byte[message.bytes().length];
      for (int index = 0; index < Carrier.count(message.bytes().length); index++) {
        Carrier chunk = Carrier.chunk(message, index);
        String encoded = chunk.encode();
        assertTrue(encoded.getBytes(StandardCharsets.UTF_8).length < 7900);
        Carrier decoded = Carrier.decode(encoded, limits, self);
        assertEquals(index, decoded.index());
        assertEquals(message.messageId(), decoded.messageId());
        assertEquals(self, decoded.target());
        System.arraycopy(
            decoded.bytes(), 0, reconstructed, index * Carrier.CHUNK_BYTES, decoded.bytes().length);
      }
      assertArrayEquals(message.bytes(), reconstructed);
    }
  }

  @Test
  void echoesDiscardBeforeBase64DecodingOrDimensionsAllocation() {
    WireMessage message = wire(self, null, "a");
    String own = Carrier.chunk(message, 0).encode();
    assertNull(
        Carrier.decode(own.substring(0, own.lastIndexOf('|') + 1) + "not-base64", limits, self));
  }

  @Test
  void rejectsVersionFieldsDimensionsAndNoncanonicalBase64() {
    String frame = Carrier.chunk(wire(UUID.randomUUID(), null, "a"), 0).encode();
    for (String malformed :
        new String[] {
          frame.replaceFirst("1\\|", "2|"),
          frame + "|extra",
          frame.replace("|test.events|", "|INVALID|"),
          frame.replace("|0|1|1|", "|1|1|1|"),
          frame.replace("|0|1|1|", "|0|2|1|"),
          frame.replace("|0|1|1|", "|0|1|-1|"),
          frame.replace("|0|1|1|", "|0|1|9000000|"),
          frame.replace("YQ==", "YQ"),
          frame.replace("YQ==", "Yg==AAAA"),
          "x".repeat(7900),
          "中".repeat(3000)
        }) {
      assertThrows(IllegalArgumentException.class, () -> Carrier.decode(malformed, limits, self));
    }
    Carrier oversized =
        new Carrier(
            UUID.randomUUID(), null, EVENTS.name(), UUID.randomUUID(), 0, 1, 6000, new byte[6000]);
    assertThrows(IllegalArgumentException.class, oversized::encode);
  }

  @Test
  void limitsAndNodeChannelsAreStrict() {
    assertEquals(48, PgTransport.inboxChannel(self).length());
    assertTrue(PgTransport.inboxChannel(self).matches("[a-z0-9_]+"));
    assertThrows(
        IllegalArgumentException.class,
        () -> new NotificationLimits(1, 1, 1, 1, 1, Duration.ZERO, 1));
    assertThrows(
        IllegalArgumentException.class,
        () -> new NotificationLimits(2, 1, 1, 1, 1, Duration.ofSeconds(1), 1));
  }
}
