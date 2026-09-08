package fun.fengwk.kkstudio.harness.runtime.model.provider.codec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderToolCallDiagnostic;

/** {@link ProviderToolCallDiagnosticJsonCodec} 严格性与格式契约测试。 */
class ProviderToolCallDiagnosticJsonCodecTest {

  private final ProviderToolCallDiagnosticJsonCodec codec =
      new ProviderToolCallDiagnosticJsonCodec();

  @Test
  void roundTripsDiagnostic() {
    ProviderToolCallDiagnostic diagnostic =
        new ProviderToolCallDiagnostic(
            1,
            "call-bad-1",
            "shell_exec",
            "{\"cmd\": \"echo unclosed string",
            "JSON arguments parse failed");

    String encoded = codec.encode(diagnostic);
    ProviderToolCallDiagnostic decoded = codec.decode(encoded);

    assertEquals(diagnostic, decoded);
    assertEquals(encoded, codec.encode(decoded));

    // 支持 id 与 name 为 null 的情况
    ProviderToolCallDiagnostic nullableFields =
        new ProviderToolCallDiagnostic(0, null, null, "partial", "error message");
    String encodedNullable = codec.encode(nullableFields);
    assertEquals(nullableFields, codec.decode(encodedNullable));
  }

  @Test
  void rejectsUnknownField() {
    ObjectNode node = codec.encodeNode(sampleDiagnostic());
    node.put("extraField", "unexpected");
    assertThrows(IllegalArgumentException.class, () -> codec.decode(node.toString()));
  }

  @Test
  void rejectsDuplicateField() {
    String jsonWithDup =
        """
        {
          "type": "tool_call_diagnostic",
          "callIndex": 0,
          "id": "call-1",
          "name": "read",
          "partialArguments": "{}",
          "message": "err",
          "name": "read"
        }
        """;
    assertThrows(IllegalArgumentException.class, () -> codec.decode(jsonWithDup));
  }

  @Test
  void rejectsInvalidInvariants() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new ProviderToolCallDiagnostic(-1, "call-1", "read", "{}", "err"));
    assertThrows(
        IllegalArgumentException.class,
        () -> new ProviderToolCallDiagnostic(0, "", "read", "{}", "err"));
    assertThrows(
        IllegalArgumentException.class,
        () -> new ProviderToolCallDiagnostic(0, "call-1", "", "{}", "err"));
    assertThrows(
        IllegalArgumentException.class,
        () -> new ProviderToolCallDiagnostic(0, "call-1", "read", "{}", ""));
    assertThrows(
        IllegalArgumentException.class,
        () -> new ProviderToolCallDiagnostic(0, "call-1", "read", "{}", "   "));
    assertThrows(
        IllegalArgumentException.class,
        () -> new ProviderToolCallDiagnostic(0, "call-1", "read", "{}", null));
  }

  @Test
  void rejectsInvalidJsonPayloads() {
    assertThrows(IllegalArgumentException.class, () -> codec.decode("[]"));
    assertThrows(IllegalArgumentException.class, () -> codec.decode("\"string\""));
    assertThrows(IllegalArgumentException.class, () -> codec.decode(""));

    // wrong type
    ObjectNode wrongType = codec.encodeNode(sampleDiagnostic());
    wrongType.put("type", "other");
    assertThrows(IllegalArgumentException.class, () -> codec.decode(wrongType.toString()));

    // negative callIndex in json
    ObjectNode negativeIndex = codec.encodeNode(sampleDiagnostic());
    negativeIndex.put("callIndex", -1);
    assertThrows(IllegalArgumentException.class, () -> codec.decode(negativeIndex.toString()));

    // missing field
    ObjectNode missingField = codec.encodeNode(sampleDiagnostic());
    missingField.remove("message");
    assertThrows(IllegalArgumentException.class, () -> codec.decode(missingField.toString()));

    // overflow callIndex
    ObjectNode overflowIndex = codec.encodeNode(sampleDiagnostic());
    overflowIndex.put("callIndex", ((long) Integer.MAX_VALUE) + 1L);
    assertThrows(IllegalArgumentException.class, () -> codec.decode(overflowIndex.toString()));

    // extra sensitive field does not leak field name or value
    String sensitiveKey = "SECRET_TOKEN_KEY_12345";
    ObjectNode extraField = codec.encodeNode(sampleDiagnostic());
    extraField.put(sensitiveKey, "secret_val");
    IllegalArgumentException exExtra =
        assertThrows(IllegalArgumentException.class, () -> codec.decode(extraField.toString()));
    assertFalse(exExtra.getMessage().contains(sensitiveKey));
    assertFalse(exExtra.getMessage().contains("secret_val"));
    assertNull(exExtra.getCause());

    // malformed JSON does not leak cause
    IllegalArgumentException exMalformed =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                codec.decode(
                    "{\"type\":\"tool_call_diagnostic\",\"secret\":\"SECRET_LEAK\"malformed"));
    assertFalse(exMalformed.getMessage().contains("SECRET_LEAK"));
    assertNull(exMalformed.getCause());

    // fractional callIndex (e.g. 0.5) must be rejected
    ObjectNode fractionalIndex = codec.encodeNode(sampleDiagnostic());
    fractionalIndex.put("callIndex", 0.5);
    assertThrows(IllegalArgumentException.class, () -> codec.decode(fractionalIndex.toString()));

    // null checks
    assertThrows(NullPointerException.class, () -> codec.encode(null));
    assertThrows(NullPointerException.class, () -> codec.decode(null));
  }

  @Test
  void normalizesAndRoundTripsNullPartialArgumentsAsEmptyObservedPrefix() {
    ProviderToolCallDiagnostic diagnostic =
        new ProviderToolCallDiagnostic(
            1, "call-null-args", "fetch", null, "tool call truncated before arguments received");

    String encoded = codec.encode(diagnostic);
    ProviderToolCallDiagnostic decoded = codec.decode(encoded);

    assertEquals(diagnostic.callIndex(), decoded.callIndex());
    assertEquals(diagnostic.id(), decoded.id());
    assertEquals(diagnostic.name(), decoded.name());
    assertEquals("", decoded.partialArguments());
    assertEquals(diagnostic.message(), decoded.message());
  }

  private ProviderToolCallDiagnostic sampleDiagnostic() {
    return new ProviderToolCallDiagnostic(
        0, "call-123", "execute_command", "{\"command\":\"ls\"}", "argument validation failed");
  }
}
