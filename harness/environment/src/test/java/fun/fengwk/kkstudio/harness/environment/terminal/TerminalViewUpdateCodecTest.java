package fun.fengwk.kkstudio.harness.environment.terminal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.IntNode;
import com.fasterxml.jackson.databind.node.LongNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.environment.terminal.TerminalView.Color;
import fun.fengwk.kkstudio.harness.environment.terminal.TerminalView.CursorShape;
import fun.fengwk.kkstudio.harness.environment.terminal.TerminalView.InputModes;
import fun.fengwk.kkstudio.harness.environment.terminal.TerminalView.Line;
import fun.fengwk.kkstudio.harness.environment.terminal.TerminalView.MouseFormat;
import fun.fengwk.kkstudio.harness.environment.terminal.TerminalView.MouseMode;
import fun.fengwk.kkstudio.harness.environment.terminal.TerminalView.Slot;
import fun.fengwk.kkstudio.harness.environment.terminal.TerminalView.SlotKind;
import fun.fengwk.kkstudio.harness.environment.terminal.TerminalView.Style;
import fun.fengwk.kkstudio.harness.environment.terminal.TerminalViewUpdate.Kind;
import fun.fengwk.kkstudio.harness.environment.terminal.TerminalViewUpdate.RowChange;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** TerminalViewUpdateCodec 的 canonical roundtrip、数值保真、字典规范与负向边界回归。 */
class TerminalViewUpdateCodecTest {

  private static final Class<TerminalViewUpdateCodec.TerminalViewUpdateException> INVALID =
      TerminalViewUpdateCodec.TerminalViewUpdateException.class;

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final TerminalViewUpdateCodec CODEC = new TerminalViewUpdateCodec();

  @Test
  void resetFixtureRoundtripsCanonically() {
    String fixture = fixture("reset-update.json");
    TerminalViewUpdate decoded = CODEC.decode(fixture);
    assertEquals(TerminalViewSamples.reset(), decoded);
    assertEquals(fixture, CODEC.encode(decoded));

    Line history = decoded.historyAppend().get(0);
    assertEquals(Style.DEFAULT, history.slots().get(0).style());
    assertEquals(0x0000, history.slots().get(1).code());
    assertEquals(0xd800, history.slots().get(2).code());
    assertEquals(TerminalViewSamples.RED, history.slots().get(2).style());
    assertEquals(0xfeff, history.slots().get(3).code());
    assertEquals(SlotKind.DWC, history.slots().get(4).kind());
    assertEquals(0xe000, history.slots().get(4).code());
    assertEquals(SlotKind.EMPTY, history.slots().get(5).kind());
    assertEquals(0, history.slots().get(5).code());
  }

  @Test
  void patchFixtureRoundtripsCanonically() {
    String fixture = fixture("patch-update.json");
    TerminalViewUpdate decoded = CODEC.decode(fixture);
    assertEquals(TerminalViewSamples.patch(), decoded);
    assertEquals(fixture, CODEC.encode(decoded));
  }

  @Test
  void metadataOnlyPatchUsesEmptyStyles() {
    String fixture = fixture("patch-metadata.json");
    TerminalViewUpdate decoded = CODEC.decode(fixture);
    assertEquals(TerminalViewSamples.metadataOnlyPatch(), decoded);
    assertTrue(decoded.historyAppend().isEmpty());
    assertTrue(decoded.screenRows().isEmpty());
    assertTrue(fixture.contains("\"styles\":[]"), fixture);
    assertEquals(fixture, CODEC.encode(decoded));
  }

  @Test
  void everyIndexedColorAndFlagRoundtrip() {
    TerminalViewUpdate update = colorAndFlagView();
    TerminalViewUpdate decoded = CODEC.decode(CODEC.encode(update));
    assertEquals(update, decoded);
    assertEquals(0x0000FFFF, decoded.screenRows().get(0).line().slots().get(0).code());
  }

  @Test
  void rgbExtremesEncodeAsExpectedIntegers() {
    TerminalViewUpdate update = rgbExtremesView();
    String json = CODEC.encode(update);
    // 黑色 RGB 编码为 0（而非缺省的 -1），白色为 16777215。
    assertTrue(json.contains("\"styles\":[[0,"), json);
    assertTrue(json.contains("[16777215,"), json);
    assertEquals(update, CODEC.decode(json));
  }

  @Test
  void rejectsMalformedTopLevelInput() {
    String valid = CODEC.encode(TerminalViewSamples.reset());
    assertThrows(INVALID, () -> CODEC.decode("[]"));
    assertThrows(INVALID, () -> CODEC.decode("null"));
    assertThrows(INVALID, () -> CODEC.decode("not-json"));
    assertThrows(INVALID, () -> CODEC.decode(null));
    // 尾随 token。
    assertThrows(INVALID, () -> CODEC.decode(valid + "{}"));
    // 重复字段。
    assertThrows(
        INVALID,
        () -> CODEC.decode(withReplacement(valid, "\"version\":7", "\"version\":7,\"version\":8")));
    // 未知字段与缺失字段。
    assertThrows(INVALID, () -> CODEC.decode(withExtraField(valid, "extra")));
    assertThrows(
        INVALID,
        () -> CODEC.decode(withoutField(CODEC.encode(TerminalViewSamples.patch()), "cols")));
  }

  @Test
  void rejectsNonCanonicalUuid() {
    String valid = CODEC.encode(TerminalViewSamples.reset());
    String upper =
        withReplacement(
            valid, "11111111-1111-1111-1111-111111111111", "11111111-1111-1111-1111-11111111111A");
    assertThrows(INVALID, () -> CODEC.decode(upper));
  }

  @Test
  void rejectsBadNumbers() {
    String valid = CODEC.encode(TerminalViewSamples.reset());
    assertThrows(INVALID, () -> CODEC.decode(withNumber(valid, "version", 7.0)));
    assertThrows(INVALID, () -> CODEC.decode(withText(valid, "version", "7")));
    assertThrows(INVALID, () -> CODEC.decode(withNumber(valid, "version", 0)));
    assertThrows(INVALID, () -> CODEC.decode(withNumber(valid, "inputModeRevision", 0)));
    assertThrows(
        INVALID,
        () ->
            CODEC.decode(
                withNumber(
                    CODEC.encode(TerminalViewSamples.patch()),
                    "baseVersion",
                    TerminalLimits.MAX_SAFE_INTEGER + 1)));
  }

  @Test
  void rejectsBadDimensionsAndIndices() {
    String valid = CODEC.encode(TerminalViewSamples.reset());
    assertThrows(
        INVALID, () -> CODEC.decode(withNumber(valid, "cols", TerminalLimits.MIN_COLUMNS - 1)));
    assertThrows(
        INVALID, () -> CODEC.decode(withNumber(valid, "rows", TerminalLimits.MAX_ROWS + 1)));
    assertThrows(INVALID, () -> CODEC.decode(scaleLineIdentity(valid, 0L)));
    assertThrows(
        INVALID, () -> CODEC.decode(scaleLineIdentity(valid, TerminalLimits.MAX_SAFE_INTEGER + 1)));
    assertThrows(INVALID, () -> CODEC.decode(setStyleIndex(valid, 0, 0, 99)));
    assertThrows(INVALID, () -> CODEC.decode(truncateSlots(valid, 0, 0)));
  }

  @Test
  void rejectsStyleDictionaryViolations() {
    String valid = CODEC.encode(TerminalViewSamples.reset());
    // 重复字典项。
    ObjectNode duplicate = read(valid);
    ((ArrayNode) duplicate.get("styles")).add(duplicate.get("styles").get(0).deepCopy());
    assertThrows(INVALID, () -> CODEC.decode(write(duplicate)));
    // 未被引用的字典项。
    ObjectNode unused = read(valid);
    ArrayNode unusedStyles = (ArrayNode) unused.get("styles");
    ArrayNode extra = unusedStyles.addArray();
    extra.add(0);
    extra.add(0);
    extra.add(0);
    assertThrows(INVALID, () -> CODEC.decode(write(unused)));
    // 非 first-seen 顺序：索引 1 先于索引 0 出现。
    assertThrows(INVALID, () -> CODEC.decode(setStyleIndex(valid, 0, 0, 1)));
    // 有槽却引用空字典。
    assertThrows(INVALID, () -> CODEC.decode(emptyStyles(valid)));
  }

  @Test
  void rejectsWrongBaselineAndDuplicateRows() {
    String resetJson = CODEC.encode(TerminalViewSamples.reset());
    assertThrows(INVALID, () -> CODEC.decode(withNumber(resetJson, "baseVersion", 5)));
    String patchJson = CODEC.encode(TerminalViewSamples.patch());
    assertThrows(INVALID, () -> CODEC.decode(withNull(patchJson, "baseVersion")));
    assertThrows(INVALID, () -> CODEC.decode(withNumber(patchJson, "version", 7)));
    assertThrows(INVALID, () -> CODEC.decode(appendDuplicateRow(patchJson)));
  }

  @Test
  void rejectsSlotShapeViolations() {
    String valid = CODEC.encode(TerminalViewSamples.reset());
    assertThrows(INVALID, () -> CODEC.decode(setSlotField(valid, 0, 0, 0, 3))); // 未知 kind
    assertThrows(INVALID, () -> CODEC.decode(setSlotField(valid, 0, 0, 0, -1))); // 负 kind
    assertThrows(INVALID, () -> CODEC.decode(setSlotField(valid, 0, 5, 1, 1))); // EMPTY code != 0
    assertThrows(INVALID, () -> CODEC.decode(setSlotField(valid, 0, 4, 1, 0x1234))); // DWC code 错
    assertThrows(INVALID, () -> CODEC.decode(setSlotField(valid, 0, 0, 1, 0x10000))); // code 越界
    assertThrows(INVALID, () -> CODEC.decode(shortenSlot(valid, 0, 0)));
  }

  @Test
  void decodeRejectsOversizedInput() {
    String huge = "\"" + "x".repeat(TerminalLimits.MAX_MESSAGE_BYTES + 1) + "\"";
    assertThrows(INVALID, () -> CODEC.decode(huge));
  }

  @Test
  void errorsDoNotLeakPayloadOrParseCause() {
    String secret = "SUPER_SECRET_PAYLOAD_TOKEN";
    String json = withExtraField(CODEC.encode(TerminalViewSamples.reset()), secret);
    TerminalViewUpdateCodec.TerminalViewUpdateException unknown =
        assertThrows(INVALID, () -> CODEC.decode(json));
    assertFalse(unknown.getMessage().contains(secret));
    assertNull(unknown.getCause());
    TerminalViewUpdateCodec.TerminalViewUpdateException parse =
        assertThrows(INVALID, () -> CODEC.decode("{\"leak\":\"" + secret));
    assertFalse(parse.getMessage().contains(secret));
    assertNull(parse.getCause());
  }

  private static TerminalViewUpdate colorAndFlagView() {
    int columns = 256;
    List<Slot> row0 = new ArrayList<>(columns);
    List<Slot> row1 = new ArrayList<>(columns);
    for (int index = 0; index < columns; index++) {
      row0.add(new Slot(SlotKind.UNIT, 0xFFFF, flagged(new Color.Indexed(index), index)));
      row1.add(
          new Slot(SlotKind.UNIT, 0xFFFF, flagged(new Color.Indexed((index + 128) % 256), index)));
    }
    return new TerminalViewUpdate(
        Kind.RESET,
        TerminalViewSamples.TERMINAL_ID,
        TerminalViewSamples.STREAM_ID,
        null,
        1L,
        columns,
        2,
        false,
        0,
        0,
        0,
        true,
        null,
        1L,
        modes(),
        0,
        List.of(),
        List.of(
            new RowChange(0, new Line(70L, false, row0)),
            new RowChange(1, new Line(71L, true, row1))));
  }

  private static TerminalViewUpdate rgbExtremesView() {
    List<Slot> row0 =
        List.of(
            new Slot(
                SlotKind.UNIT,
                0x0041,
                new Style(
                    new Color.Rgb(0, 0, 0),
                    null,
                    false,
                    false,
                    false,
                    false,
                    false,
                    false,
                    false,
                    false)),
            new Slot(
                SlotKind.UNIT,
                0x0042,
                new Style(
                    new Color.Rgb(255, 255, 255),
                    null,
                    false,
                    false,
                    false,
                    false,
                    false,
                    false,
                    false,
                    false)),
            new Slot(
                SlotKind.UNIT,
                0x0043,
                new Style(
                    null,
                    new Color.Rgb(0, 0, 1),
                    false,
                    false,
                    false,
                    false,
                    false,
                    false,
                    false,
                    false)),
            new Slot(
                SlotKind.UNIT,
                0x0044,
                new Style(
                    new Color.Indexed(255),
                    null,
                    false,
                    false,
                    false,
                    false,
                    false,
                    false,
                    false,
                    false)),
            new Slot(SlotKind.UNIT, 0x0045, Style.DEFAULT));
    List<Slot> row1 =
        List.of(
            new Slot(SlotKind.UNIT, 0x0046, Style.DEFAULT),
            new Slot(SlotKind.UNIT, 0x0047, Style.DEFAULT),
            new Slot(SlotKind.UNIT, 0x0048, Style.DEFAULT),
            new Slot(SlotKind.UNIT, 0x0049, Style.DEFAULT),
            new Slot(SlotKind.UNIT, 0x004A, Style.DEFAULT));
    return new TerminalViewUpdate(
        Kind.RESET,
        TerminalViewSamples.TERMINAL_ID,
        TerminalViewSamples.STREAM_ID,
        null,
        1L,
        5,
        2,
        false,
        0,
        0,
        0,
        true,
        CursorShape.BLINK_UNDERLINE,
        1L,
        modes(),
        0,
        List.of(),
        List.of(
            new RowChange(0, new Line(80L, false, row0)),
            new RowChange(1, new Line(81L, false, row1))));
  }

  private static Style flagged(Color color, int flags) {
    return new Style(
        color,
        null,
        (flags & 0x01) != 0,
        (flags & 0x02) != 0,
        (flags & 0x04) != 0,
        (flags & 0x08) != 0,
        (flags & 0x10) != 0,
        (flags & 0x20) != 0,
        (flags & 0x40) != 0,
        (flags & 0x80) != 0);
  }

  private static String fixture(String name) {
    try (InputStream input = TerminalViewUpdateCodecTest.class.getResourceAsStream(name)) {
      assertNotNull(input, name);
      return new String(input.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException error) {
      throw new UncheckedIOException(error);
    }
  }

  private static String withReplacement(String json, String from, String to) {
    assertTrue(json.contains(from), "fixture must contain: " + from);
    return json.replace(from, to);
  }

  private static ObjectNode read(String json) {
    try {
      return (ObjectNode) MAPPER.readTree(json);
    } catch (JsonProcessingException error) {
      throw new UncheckedIOException(error);
    }
  }

  private static String write(JsonNode node) {
    try {
      return MAPPER.writeValueAsString(node);
    } catch (JsonProcessingException error) {
      throw new UncheckedIOException(error);
    }
  }

  private static String withExtraField(String json, String field) {
    ObjectNode root = read(json);
    root.put(field, 1);
    return write(root);
  }

  private static String withoutField(String json, String field) {
    ObjectNode root = read(json);
    assertNotNull(root.remove(field), field);
    return write(root);
  }

  private static String withNumber(String json, String field, double value) {
    ObjectNode root = read(json);
    root.put(field, value);
    return write(root);
  }

  private static String withNumber(String json, String field, long value) {
    ObjectNode root = read(json);
    root.put(field, value);
    return write(root);
  }

  private static String withText(String json, String field, String value) {
    ObjectNode root = read(json);
    root.put(field, value);
    return write(root);
  }

  private static String withNull(String json, String field) {
    ObjectNode root = read(json);
    root.putNull(field);
    return write(root);
  }

  private static String scaleLineIdentity(String json, long id) {
    ObjectNode root = read(json);
    ArrayNode line = (ArrayNode) ((ArrayNode) root.get("historyAppend")).get(0);
    line.set(0, LongNode.valueOf(id));
    return write(root);
  }

  private static String setStyleIndex(String json, int lineIndex, int slotIndex, int styleIndex) {
    ObjectNode root = read(json);
    ArrayNode line = (ArrayNode) ((ArrayNode) root.get("historyAppend")).get(lineIndex);
    ((ArrayNode) ((ArrayNode) line.get(2)).get(slotIndex)).set(2, IntNode.valueOf(styleIndex));
    return write(root);
  }

  private static String truncateSlots(String json, int lineIndex, int keep) {
    ObjectNode root = read(json);
    ArrayNode line = (ArrayNode) ((ArrayNode) root.get("historyAppend")).get(lineIndex);
    ArrayNode slots = MAPPER.createArrayNode();
    for (int index = 0; index < keep; index++) {
      slots.add(((ArrayNode) line.get(2)).get(index).deepCopy());
    }
    line.set(2, slots);
    return write(root);
  }

  private static String shortenSlot(String json, int lineIndex, int slotIndex) {
    ObjectNode root = read(json);
    ArrayNode line = (ArrayNode) ((ArrayNode) root.get("historyAppend")).get(lineIndex);
    ArrayNode slot = (ArrayNode) ((ArrayNode) line.get(2)).get(slotIndex);
    slot.remove(1);
    return write(root);
  }

  private static String setSlotField(
      String json, int lineIndex, int slotIndex, int field, long value) {
    ObjectNode root = read(json);
    ArrayNode line = (ArrayNode) ((ArrayNode) root.get("historyAppend")).get(lineIndex);
    ArrayNode slot = (ArrayNode) ((ArrayNode) line.get(2)).get(slotIndex);
    slot.set(field, LongNode.valueOf(value));
    return write(root);
  }

  private static String emptyStyles(String json) {
    ObjectNode root = read(json);
    root.set("styles", MAPPER.createArrayNode());
    return write(root);
  }

  private static String appendDuplicateRow(String json) {
    ObjectNode root = read(json);
    ArrayNode screenRows = (ArrayNode) root.get("screenRows");
    ObjectNode copy = (ObjectNode) screenRows.get(0).deepCopy();
    ((ArrayNode) copy.get("line")).set(0, LongNode.valueOf(999L));
    screenRows.add(copy);
    return write(root);
  }

  private static InputModes modes() {
    return new InputModes(false, false, false, false, false, MouseMode.NONE, MouseFormat.XTERM);
  }
}
