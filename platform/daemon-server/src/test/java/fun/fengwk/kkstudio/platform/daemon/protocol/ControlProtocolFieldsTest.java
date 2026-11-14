package fun.fengwk.kkstudio.platform.daemon.protocol;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.protobuf.UnknownFieldSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.util.regex.Pattern;

/** 字段级校验原语的分支契约：每类拒绝都给出固定文案，通过时返回原值。 */
class ControlProtocolFieldsTest {

  private static final Pattern LOWER_SHA = Pattern.compile("^[0-9a-f]{64}$");

  @Test
  void requireTextRejectsMissingBlankAndOversized() {
    assertEquals("value", ControlProtocolFields.requireText(true, "value", "f", 5, true));
    assertEquals(
        "f is required",
        assertMessage(() -> ControlProtocolFields.requireText(false, "value", "f", 5, true)));
    assertEquals(
        "f is required",
        assertMessage(() -> ControlProtocolFields.requireText(true, null, "f", 5, true)));
    assertEquals(
        "f must not be blank",
        assertMessage(() -> ControlProtocolFields.requireText(true, " ", "f", 5, true)));
    assertEquals(
        "f exceeds 3 characters",
        assertMessage(() -> ControlProtocolFields.requireText(true, "abcd", "f", 3, false)));
    assertDoesNotThrow(() -> ControlProtocolFields.requireText(true, " ", "f", 5, false));
  }

  @Test
  void requireTextRejectsUnpairedSurrogate() {
    assertEquals(
        "f must be valid Unicode",
        assertMessage(() -> ControlProtocolFields.requireText(true, "\ud800", "f", 5, false)));
  }

  @Test
  void requireUtf8TextCountsBytesNotChars() {
    assertEquals("文本", ControlProtocolFields.requireUtf8Text(true, "文本", "f", 6, true));
    assertEquals(
        "f exceeds 5 UTF-8 bytes",
        assertMessage(() -> ControlProtocolFields.requireUtf8Text(true, "文本", "f", 5, true)));
    assertEquals(
        "f is required",
        assertMessage(() -> ControlProtocolFields.requireUtf8Text(false, "x", "f", 5, true)));
    assertEquals(
        "f must not be blank",
        assertMessage(() -> ControlProtocolFields.requireUtf8Text(true, "", "f", 5, true)));
  }

  @Test
  void requirePatternRejectsMissingAndMismatch() {
    String sha = "a".repeat(64);
    assertEquals(sha, ControlProtocolFields.requirePattern(true, sha, "f", LOWER_SHA));
    assertEquals(
        "f is required",
        assertMessage(() -> ControlProtocolFields.requirePattern(false, sha, "f", LOWER_SHA)));
    assertEquals(
        "f has invalid syntax",
        assertMessage(
            () -> ControlProtocolFields.requirePattern(true, "A".repeat(64), "f", LOWER_SHA)));
  }

  @Test
  void requireUuidRejectsMissingAndNonCanonical() {
    assertDoesNotThrow(
        () -> ControlProtocolFields.requireUuid(true, "11111111-1111-4111-8111-111111111111", "f"));
    assertEquals(
        "f is required", assertMessage(() -> ControlProtocolFields.requireUuid(false, "x", "f")));
    assertEquals(
        "f must be a canonical lowercase UUID",
        assertMessage(
            () ->
                ControlProtocolFields.requireUuid(
                    true, "11111111-1111-4111-8111-11111111111A", "f")));
  }

  @Test
  void requireInt64AndPositiveBoundBothEnds() {
    assertDoesNotThrow(() -> ControlProtocolFields.requireInt64(true, 1, "f", 1, 2));
    assertEquals(
        "f must be present and in 1..2",
        assertMessage(() -> ControlProtocolFields.requireInt64(false, 1, "f", 1, 2)));
    assertEquals(
        "f must be present and in 1..2",
        assertMessage(() -> ControlProtocolFields.requireInt64(true, 0, "f", 1, 2)));
    assertEquals(
        "f must be present and in 1..2",
        assertMessage(() -> ControlProtocolFields.requireInt64(true, 3, "f", 1, 2)));
    assertEquals(
        "f must be present and in 1..9223372036854775807",
        assertMessage(() -> ControlProtocolFields.requirePositive(true, 0, "f")));
  }

  @Test
  void requireZoneRejectsMissingAndInvalidZone() {
    assertDoesNotThrow(() -> ControlProtocolFields.requireZone(true, "UTC", "f"));
    assertEquals(
        "f is required", assertMessage(() -> ControlProtocolFields.requireZone(false, "UTC", "f")));
    assertEquals(
        "f must be a valid ZoneId",
        assertMessage(() -> ControlProtocolFields.requireZone(true, "Not/AZone", "f")));
  }

  @Test
  void requireSingleLineRejectsSurroundingWhitespaceAndControlChars() {
    assertDoesNotThrow(() -> ControlProtocolFields.requireSingleLine(true, "abc", "f", 10));
    assertEquals(
        "f must not have surrounding whitespace",
        assertMessage(() -> ControlProtocolFields.requireSingleLine(true, " abc", "f", 10)));
    assertEquals(
        "f must not contain control characters",
        assertMessage(() -> ControlProtocolFields.requireSingleLine(true, "a\tb", "f", 10)));
  }

  @Test
  void requireMediaTypeRejectsMissingAndNonCanonical() {
    assertDoesNotThrow(() -> ControlProtocolFields.requireMediaType(true, "text/plain", "f"));
    assertEquals(
        "f is required",
        assertMessage(() -> ControlProtocolFields.requireMediaType(false, "text/plain", "f")));
    assertEquals(
        "f must be canonical lowercase type/subtype",
        assertMessage(() -> ControlProtocolFields.requireMediaType(true, "Text/Plain", "f")));
  }

  @Test
  void requireBoundedMessageRejectsOversizedAndBlank() {
    assertDoesNotThrow(() -> ControlProtocolFields.requireBoundedMessage(true, "ok", "f"));
    assertEquals(
        "f must not be blank",
        assertMessage(() -> ControlProtocolFields.requireBoundedMessage(true, " ", "f")));
  }

  @Test
  void requirePresentOnlyChecksPresence() {
    assertEquals("x", ControlProtocolFields.requirePresent(true, "x", "f"));
    assertEquals(
        "f is required",
        assertMessage(() -> ControlProtocolFields.requirePresent(false, "x", "f")));
  }

  @Test
  void requireIdePathRejectsControlCharacters() {
    assertDoesNotThrow(() -> ControlProtocolFields.requireIdePath("/tmp/work", "f"));
    assertEquals(
        "f must not be blank", assertMessage(() -> ControlProtocolFields.requireIdePath("", "f")));
    assertEquals(
        "f must not contain control characters",
        assertMessage(() -> ControlProtocolFields.requireIdePath("/tmp/\u0001", "f")));
    assertEquals(
        "f must not contain control characters",
        assertMessage(() -> ControlProtocolFields.requireIdePath("/tmp/\u007f", "f")));
  }

  @Test
  void requireHttpOriginAcceptsOriginsAndRejectsEveryOtherShape() {
    assertDoesNotThrow(() -> ControlProtocolFields.requireHttpOrigin("https://a.b", "f", false));
    assertDoesNotThrow(() -> ControlProtocolFields.requireHttpOrigin("http://a.b:8080", "p", true));
    assertEquals(
        "f must be an HTTP origin",
        assertMessage(() -> ControlProtocolFields.requireHttpOrigin("http://a b", "f", false)));
    assertEquals(
        "f must be an HTTP(S) origin without userinfo, path, query or fragment",
        assertMessage(() -> ControlProtocolFields.requireHttpOrigin("ftp://a.b", "f", false)));
    assertEquals(
        "f must be an HTTP(S) origin without userinfo, path, query or fragment",
        assertMessage(
            () -> ControlProtocolFields.requireHttpOrigin("https://u:p@a.b", "f", false)));
    assertEquals(
        "f must be an HTTP(S) origin without userinfo, path, query or fragment",
        assertMessage(() -> ControlProtocolFields.requireHttpOrigin("https://a.b/x", "f", false)));
    assertEquals(
        "p must be an http://host:port proxy origin without userinfo, path, query or fragment",
        assertMessage(() -> ControlProtocolFields.requireHttpOrigin("https://a.b:80", "p", true)));
    assertEquals(
        "p must declare an explicit port",
        assertMessage(() -> ControlProtocolFields.requireHttpOrigin("http://a.b", "p", true)));
    assertEquals(
        "f port is invalid",
        assertMessage(
            () -> ControlProtocolFields.requireHttpOrigin("http://a.b:70000", "f", false)));
  }

  @Test
  void requireNoUnknownWalksNestedMessagesAndRejectsUnknownFields() {
    Envelope plain = ControlProtocolFixtures.executionExitedEnvelope();
    assertDoesNotThrow(() -> ControlProtocolFields.requireNoUnknown(plain, "envelope"));

    UnknownFieldSet unknown =
        UnknownFieldSet.newBuilder()
            .addField(100, UnknownFieldSet.Field.newBuilder().addVarint(1).build())
            .build();
    assertEquals(
        "envelope contains unknown protobuf fields",
        assertMessage(
            () ->
                ControlProtocolFields.requireNoUnknown(
                    plain.toBuilder().mergeUnknownFields(unknown).build(), "envelope")));

    Envelope nested =
        ControlProtocolFixtures.invokeEnvelope().toBuilder()
            .setInvoke(
                ControlProtocolFixtures.invokeEnvelope().getInvoke().toBuilder()
                    .mergeUnknownFields(unknown))
            .build();
    assertEquals(
        "envelope.invoke contains unknown protobuf fields",
        assertMessage(() -> ControlProtocolFields.requireNoUnknown(nested, "envelope")));

    Envelope repeated = ControlProtocolFixtures.completedEnvelope();
    CapabilityResult result = repeated.getCompleted().getResult();
    CapabilityResult withUnknown =
        result.toBuilder()
            .setContents(0, result.getContents(0).toBuilder().mergeUnknownFields(unknown).build())
            .build();
    Envelope completed =
        repeated.toBuilder()
            .setCompleted(repeated.getCompleted().toBuilder().setResult(withUnknown))
            .build();
    assertEquals(
        "envelope.completed.result.contents[0] contains unknown protobuf fields",
        assertMessage(() -> ControlProtocolFields.requireNoUnknown(completed, "envelope")));
  }

  private static String assertMessage(Executable executable) {
    ControlProtocolException error = assertThrows(ControlProtocolException.class, executable);
    String message = error.getMessage();
    assertTrue(message != null && !message.isBlank(), "failure must carry a message");
    return message;
  }
}
