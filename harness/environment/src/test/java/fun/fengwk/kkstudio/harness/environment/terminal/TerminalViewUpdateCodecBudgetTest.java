package fun.fengwk.kkstudio.harness.environment.terminal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

/** 最大允许画面的真实 encode/decode 预算证明：183600 槽、183600 唯一样式、贴近 JS 安全整数上限。 */
class TerminalViewUpdateCodecBudgetTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Test
  void maximalResetFitsBudgetAndRoundtrips() throws JsonProcessingException {
    TerminalViewUpdate update = TerminalViewSamples.maximalReset();
    TerminalViewUpdateCodec codec = new TerminalViewUpdateCodec();
    String json = codec.encode(update);
    int bytes = json.getBytes(StandardCharsets.UTF_8).length;

    // 真实生成的最大尺寸必须落在 8 MiB 载体预算以内，且远非空/缺省桩（实测 7356173 字节）。
    assertTrue(bytes < TerminalLimits.MAX_MESSAGE_BYTES, "encoded bytes=" + bytes);
    assertTrue(bytes > 7_300_000, "worst-case payload must be meaningful, bytes=" + bytes);

    JsonNode root = MAPPER.readTree(json);
    assertEquals(183600, root.get("styles").size());
    assertEquals(TerminalLimits.MAX_SAFE_INTEGER, root.get("version").longValue());
    assertEquals(TerminalLimits.MAX_SAFE_INTEGER, root.get("inputModeRevision").longValue());
    ArrayNode historyAppend = (ArrayNode) root.get("historyAppend");
    assertEquals(TerminalLimits.MAX_HISTORY_LINES, historyAppend.size());
    assertEquals(
        TerminalLimits.MAX_SAFE_INTEGER, ((ArrayNode) historyAppend.get(0)).get(0).longValue());
    // 六位 styleIndex 与最大 UTF-16 code 都真实出现。
    assertTrue(json.contains("183599"));
    assertTrue(json.contains("65535"));

    assertEquals(update, codec.decode(json));
  }
}
