package fun.fengwk.kkstudio.harness.runtime.thread;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.tool.worker.ToolResultJsonCodec;
import fun.fengwk.kkstudio.harness.tool.TextToolContent;
import fun.fengwk.kkstudio.harness.tool.ToolResult;

import java.util.List;

/** Payload encoding: bigint ids as strings; partialResults as canonical ToolResult objects. */
class ThreadEventPayloadsTest {
  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Test
  void encodesBigintInvocationIdAsStringAndCanonicalPartialResults() throws Exception {
    ToolResult result =
        new ToolResult("call-1", List.of(new TextToolContent("ok")), false, "{}", false);
    String json =
        ThreadEventPayloads.of(
            "invocationId",
            1234567890123456789L,
            "toolCallId",
            "call-1",
            "partialResults",
            List.of(ToolResultJsonCodec.encodeNode(result)));
    JsonNode root = MAPPER.readTree(json);
    assertTrue(root.get("invocationId").isTextual());
    assertEquals("1234567890123456789", root.get("invocationId").asText());
    assertTrue(root.get("partialResults").isArray());
    JsonNode first = root.get("partialResults").get(0);
    assertTrue(first.isObject());
    assertEquals("call-1", first.get("toolCallId").asText());
    assertTrue(first.get("contents").isArray());
    assertEquals("text", first.get("contents").get(0).get("type").asText());
    assertEquals("ok", first.get("contents").get(0).get("text").asText());
  }
}
