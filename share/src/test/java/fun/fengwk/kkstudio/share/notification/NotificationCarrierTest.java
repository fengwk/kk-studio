package fun.fengwk.kkstudio.share.notification;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.UUID;

/**
 * Carrier framing contract for every transport: one format, canonical fields, defensive copies and
 * fixed, payload-free errors. Migrated from the former notification-private CarrierTest.
 */
class NotificationCarrierTest {
  private static final String TOPIC = "test.events";
  private static final NotificationLimits LIMITS = NotificationLimits.defaults();
  private final UUID self = UUID.randomUUID();

  @Test
  void emptySmallAndOversizeUnicodeAllUseSameBoundedCarrier() {
    for (String payload : new String[] {"", "hint", "你好😀|\n".repeat(30000)}) {
      NotificationPacket message =
          new NotificationPacket(UUID.randomUUID(), self, TOPIC, UUID.randomUUID(), utf8(payload));
      byte[] reconstructed = new byte[message.byteLength()];
      for (int index = 0; index < NotificationCarrier.count(message.byteLength()); index++) {
        NotificationCarrier chunk = NotificationCarrier.chunk(message, index);
        String encoded = chunk.encode();
        assertTrue(encoded.getBytes(StandardCharsets.UTF_8).length < 7900);
        NotificationCarrier decoded = NotificationCarrier.decode(encoded, LIMITS, self);
        assertEquals(index, decoded.index());
        assertEquals(message.messageId(), decoded.messageId());
        assertEquals(self, decoded.target());
        System.arraycopy(
            decoded.bytes(),
            0,
            reconstructed,
            index * NotificationCarrier.CHUNK_BYTES,
            decoded.bytes().length);
      }
      assertArrayEquals(message.bytes(), reconstructed);
    }
  }

  @Test
  void echoesDiscardBeforeBase64DecodingOrDimensionsAllocation() {
    NotificationPacket message =
        new NotificationPacket(self, null, TOPIC, UUID.randomUUID(), utf8("a"));
    String own = NotificationCarrier.chunk(message, 0).encode();
    assertNull(
        NotificationCarrier.decode(
            own.substring(0, own.lastIndexOf('|') + 1) + "not-base64", LIMITS, self));
  }

  @Test
  void rejectsVersionFieldsDimensionsAndNoncanonicalBase64() {
    String frame =
        NotificationCarrier.chunk(
                new NotificationPacket(
                    UUID.randomUUID(), null, TOPIC, UUID.randomUUID(), utf8("a")),
                0)
            .encode();
    for (String malformed :
        new String[] {
          frame.replaceFirst("1\\|", "2|"),
          frame + "|extra",
          frame.replace("|" + TOPIC + "|", "|INVALID|"),
          frame.replace("|0|1|1|", "|1|1|1|"),
          frame.replace("|0|1|1|", "|0|2|1|"),
          frame.replace("|0|1|1|", "|0|1|-1|"),
          frame.replace("|0|1|1|", "|0|1|9000000|"),
          frame.replace("YQ==", "YQ"),
          frame.replace("YQ==", "Yg==AAAA"),
          "x".repeat(7900),
          "中".repeat(3000)
        }) {
      assertThrows(
          IllegalArgumentException.class,
          () -> NotificationCarrier.decode(malformed, LIMITS, self));
    }
  }

  @Test
  void canonicalIdentitiesAndNumbersRejectAlternateFormsWithoutEchoingThem() {
    UUID publisher = UUID.fromString("abcdefab-cdef-abcd-efab-cdefabcdefab");
    String frame =
        NotificationCarrier.chunk(
                new NotificationPacket(publisher, null, TOPIC, UUID.randomUUID(), utf8("a")), 0)
            .encode();
    String[] malformed = {
      frame.replace(publisher.toString(), publisher.toString().toUpperCase(Locale.ROOT)),
      frame.replace(publisher.toString(), "1-1-1-1-1"),
      frame.replace("|0|1|1|", "|00|1|1|"),
      frame.replace("|0|1|1|", "|+0|1|1|"),
      frame.replace("|0|1|1|", "|9999999999|1|1|"),
      frame.replace("|0|1|1|", "|0|1|9999999999|"),
      frame.replace(publisher.toString(), "not-a-uuid")
    };
    for (String value : malformed) {
      IllegalArgumentException error =
          assertThrows(
              IllegalArgumentException.class,
              () -> NotificationCarrier.decode(value, LIMITS, self));
      assertFalse(
          error.getMessage().contains(publisher.toString())
              || error.getMessage().contains("9999999999")
              || error.getMessage().contains("1-1-1-1-1"),
          () -> "error must not echo the rejected input: " + error.getMessage());
    }
  }

  @Test
  void constructorEnforcesBoundsAndExactChunkLength() {
    UUID publisher = UUID.randomUUID();
    UUID id = UUID.randomUUID();
    assertEquals(2, NotificationCarrier.count(6000));
    new NotificationCarrier(publisher, null, TOPIC, id, 0, 1, 0, new byte[0]);
    new NotificationCarrier(publisher, null, TOPIC, id, 0, 2, 6000, new byte[5400]);
    new NotificationCarrier(publisher, null, TOPIC, id, 1, 2, 6000, new byte[600]);
    assertThrows(
        NullPointerException.class,
        () -> new NotificationCarrier(null, null, TOPIC, id, 0, 1, 0, new byte[0]));
    assertThrows(
        NullPointerException.class,
        () -> new NotificationCarrier(publisher, null, null, id, 0, 1, 0, new byte[0]));
    assertThrows(
        IllegalArgumentException.class,
        () -> new NotificationCarrier(publisher, null, "INVALID", id, 0, 1, 0, new byte[0]));
    assertThrows(
        NullPointerException.class,
        () -> new NotificationCarrier(publisher, null, TOPIC, null, 0, 1, 0, new byte[0]));
    assertThrows(
        NullPointerException.class,
        () -> new NotificationCarrier(publisher, null, TOPIC, id, 0, 1, 0, null));
    assertThrows(
        IllegalArgumentException.class,
        () -> new NotificationCarrier(publisher, null, TOPIC, id, 0, 1, -1, new byte[0]));
    assertThrows(
        IllegalArgumentException.class,
        () -> new NotificationCarrier(publisher, null, TOPIC, id, 0, 2, 0, new byte[0]));
    assertThrows(
        IllegalArgumentException.class,
        () -> new NotificationCarrier(publisher, null, TOPIC, id, -1, 1, 0, new byte[0]));
    assertThrows(
        IllegalArgumentException.class,
        () -> new NotificationCarrier(publisher, null, TOPIC, id, 1, 1, 0, new byte[0]));
    assertThrows(
        IllegalArgumentException.class,
        () -> new NotificationCarrier(publisher, null, TOPIC, id, 0, 1, 6000, new byte[5400]));
    assertThrows(
        IllegalArgumentException.class,
        () -> new NotificationCarrier(publisher, null, TOPIC, id, 0, 2, 6000, new byte[6000]));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new NotificationCarrier(
                publisher,
                null,
                TOPIC,
                id,
                0,
                NotificationCarrier.count(NotificationLimits.DEFAULT_MAX_MESSAGE_BYTES + 1),
                NotificationLimits.DEFAULT_MAX_MESSAGE_BYTES + 1,
                new byte[5400]));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new NotificationPacket(
                publisher,
                null,
                TOPIC,
                id,
                new byte[NotificationLimits.DEFAULT_MAX_MESSAGE_BYTES + 1]));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            NotificationCarrier.chunk(
                new NotificationPacket(publisher, null, TOPIC, id, utf8("a")), 1));
  }

  @Test
  void carrierCopiesBytesOnAccessAndHidesThemFromToString() {
    NotificationPacket message =
        new NotificationPacket(self, null, TOPIC, UUID.randomUUID(), utf8("body"));
    NotificationCarrier carrier = NotificationCarrier.chunk(message, 0);
    byte[] first = carrier.bytes();
    assertNotSame(carrier.bytes(), first);
    first[0] = 0;
    assertArrayEquals(utf8("body"), carrier.bytes());
    assertFalse(carrier.toString().contains("body"));
  }

  @Test
  void maximalPayloadFragmentsStayWithinWireBudgetAndRoundTrip() {
    int size = 8 * 1024 * 1024;
    byte[] body = new byte[size];
    for (int index = 0; index < size; index++) {
      body[index] = (byte) (index * 31);
    }
    NotificationPacket message =
        new NotificationPacket(UUID.randomUUID(), null, TOPIC, UUID.randomUUID(), body);
    assertEquals(
        (size + NotificationCarrier.CHUNK_BYTES - 1) / NotificationCarrier.CHUNK_BYTES,
        NotificationCarrier.count(size));
    byte[] reconstructed = new byte[size];
    for (int index = 0; index < NotificationCarrier.count(size); index++) {
      NotificationCarrier chunk = NotificationCarrier.chunk(message, index);
      String encoded = chunk.encode();
      assertTrue(encoded.getBytes(StandardCharsets.UTF_8).length < 7900);
      assertTrue(chunk.bytes().length <= NotificationCarrier.CHUNK_BYTES);
      NotificationCarrier decoded = NotificationCarrier.decode(encoded, LIMITS, self);
      System.arraycopy(
          decoded.bytes(),
          0,
          reconstructed,
          index * NotificationCarrier.CHUNK_BYTES,
          decoded.bytes().length);
    }
    assertArrayEquals(body, reconstructed);
  }

  @Test
  void packetBodyIsCopiedInAndOutAndNeverRendered() {
    byte[] body = utf8("secret-body");
    NotificationPacket packet =
        new NotificationPacket(UUID.randomUUID(), null, TOPIC, UUID.randomUUID(), body);
    body[0] = 0;
    assertArrayEquals(utf8("secret-body"), packet.bytes());
    assertNotSame(packet.bytes(), packet.bytes());
    byte[] copied = packet.bytes();
    copied[0] = 0;
    assertArrayEquals(utf8("secret-body"), packet.bytes());
    assertEquals(11, packet.byteLength());
    assertFalse(packet.toString().contains("secret-body"));
    assertTrue(packet.toString().contains(TOPIC));
  }

  @Test
  void countRejectsNegativeAndComputesCeilWithoutOverflow() {
    assertEquals(1, NotificationCarrier.count(0));
    assertEquals(1, NotificationCarrier.count(5400));
    assertEquals(2, NotificationCarrier.count(5401));
    assertEquals(397683, NotificationCarrier.count(Integer.MAX_VALUE));
    assertThrows(IllegalArgumentException.class, () -> NotificationCarrier.count(-1));
  }

  @Test
  void limitsRejectZeroAndUnderSizedBudgets() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new NotificationLimits(1, 1, 1, 1, 1, Duration.ZERO, 1));
    assertThrows(
        IllegalArgumentException.class,
        () -> new NotificationLimits(2, 1, 1, 1, 1, Duration.ofSeconds(1), 1));
    assertThrows(
        IllegalArgumentException.class,
        () -> new NotificationLimits(1, 1, 0, 1, 1, Duration.ofSeconds(1), 1));
    assertThrows(
        IllegalArgumentException.class,
        () -> new NotificationLimits(1, 1, 1, 1, 0, Duration.ofSeconds(1), 1));
    assertThrows(
        IllegalArgumentException.class,
        () -> new NotificationLimits(1, 1, 1, 1, 1, Duration.ofSeconds(1), 0));
  }

  private static byte[] utf8(String value) {
    return value.getBytes(StandardCharsets.UTF_8);
  }
}
