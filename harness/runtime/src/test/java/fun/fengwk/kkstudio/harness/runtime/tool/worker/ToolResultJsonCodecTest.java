package fun.fengwk.kkstudio.harness.runtime.tool.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import fun.fengwk.kkstudio.harness.tool.ArtifactRef;
import fun.fengwk.kkstudio.harness.tool.ArtifactToolContent;
import fun.fengwk.kkstudio.harness.tool.JsonToolContent;
import fun.fengwk.kkstudio.harness.tool.TextToolContent;
import fun.fengwk.kkstudio.harness.tool.ToolResult;
import java.util.List;
import org.junit.jupiter.api.Test;

class ToolResultJsonCodecTest {

  /**
   * Every persistable Tool content variant round-trips without retaining the in-memory terminate
   * hint.
   */
  @Test
  void roundTripsTextJsonAndArtifactContents() {
    ToolResult source =
        new ToolResult(
            "call",
            List.of(
                new TextToolContent("text"),
                new JsonToolContent("{\"answer\":42}"),
                new ArtifactToolContent(new ArtifactRef("88", "application/octet-stream", 3))),
            true,
            "{\"code\":7}",
            true);

    ToolResult decoded = ToolResultJsonCodec.decode(ToolResultJsonCodec.encode(source));

    assertEquals("call", decoded.toolCallId());
    assertEquals(true, decoded.error());
    assertEquals("{\"code\":7}", decoded.detailsJson());
    assertEquals(false, decoded.terminate());
    assertEquals("text", ((TextToolContent) decoded.contents().get(0)).text());
    assertEquals("{\"answer\":42}", ((JsonToolContent) decoded.contents().get(1)).json());
    assertEquals("88", ((ArtifactToolContent) decoded.contents().get(2)).artifact().artifactId());
  }

  /** Empty successful results remain valid, including the optional default details object. */
  @Test
  void roundTripsEmptySuccessfulResult() {
    ToolResult decoded =
        ToolResultJsonCodec.decode(
            ToolResultJsonCodec.encode(new ToolResult("call", List.of(), false, "{}", false)));

    assertEquals(List.of(), decoded.contents());
    assertEquals(false, decoded.error());
    assertEquals("{}", decoded.detailsJson());
  }

  /** Persisted result input is strict so malformed journal data cannot become a Session message. */
  @Test
  void rejectsMalformedOrUnknownContents() {
    assertThrows(IllegalArgumentException.class, () -> ToolResultJsonCodec.decode("[]"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ToolResultJsonCodec.decode(
                "{\"toolCallId\":\"call\",\"contents\":[],\"error\":false,\"details\":[]}"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ToolResultJsonCodec.decode(
                "{\"toolCallId\":\"call\",\"contents\":[{\"type\":\"other\"}],\"error\":false,\"details\":{}}"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ToolResultJsonCodec.decode(
                "{\"toolCallId\":\"call\",\"contents\":[{\"type\":\"text\"}],\"error\":false,\"details\":{}}"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ToolResultJsonCodec.decode(
                "{\"toolCallId\":\"call\",\"contents\":[{\"type\":\"artifact\",\"artifactId\":\"1\",\"mediaType\":\"text/plain\",\"sizeBytes\":-1}],\"error\":false,\"details\":{}}"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ToolResultJsonCodec.decode(
                "{\"toolCallId\":\"call\",\"contents\":[],\"error\":\"false\",\"details\":{}}"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ToolResultJsonCodec.decode(
                "{\"toolCallId\":\"call\",\"contents\":[],\"error\":false,\"details\":{},\"extra\":true}"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ToolResultJsonCodec.decode(
                "{\"toolCallId\":\"call\",\"contents\":[{\"type\":\"json\"}],\"error\":false,\"details\":{}}"));
    assertThrows(
        IllegalArgumentException.class,
        () -> ToolResultJsonCodec.decode("{\"toolCallId\":\"call\"}"));
    assertThrows(IllegalArgumentException.class, () -> ToolResultJsonCodec.decode("{"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ToolResultJsonCodec.decode(
                "{\"toolCallId\":\"call\",\"contents\":{},\"error\":false,\"details\":{}}"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ToolResultJsonCodec.decode(
                "{\"toolCallId\":\"call\",\"contents\":[1],\"error\":false,\"details\":{}}"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ToolResultJsonCodec.decode(
                "{\"toolCallId\":\" \",\"contents\":[],\"error\":false,\"details\":{}}"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ToolResultJsonCodec.decode(
                "{\"toolCallId\":\"call\",\"contents\":[],\"error\":false,\"missing\":true}"));
  }
}
