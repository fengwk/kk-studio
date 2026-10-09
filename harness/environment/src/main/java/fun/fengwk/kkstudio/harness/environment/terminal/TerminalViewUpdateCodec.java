package fun.fengwk.kkstudio.harness.environment.terminal;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import fun.fengwk.kkstudio.harness.common.json.BoundedJsonWriter;
import fun.fengwk.kkstudio.harness.common.resource.ResourceRef;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * {@link TerminalViewUpdate} 的唯一 canonical JSON codec。
 *
 * <p>RESET 与 PATCH 共用同一顶层结构，字段固定为 {@code type/terminalId/streamId/baseVersion/version/cols/rows/
 * alternate/history/cursor/inputModeRevision/inputModes/historyTrim/historyAppend/screenRows/styles}。{@code
 * baseVersion} 在 RESET 显式为 JSON {@code null}；枚举与 type 使用现有大写名称，UUID 使用 canonical 文本。绝不使用 Jackson 默认
 * record 序列化，避免出现第二个 wire 格式。
 *
 * <p>样式只在传输层压缩：每行是 {@code [id, wrapped, slots]}，每槽是 {@code [kind, code, styleIndex]}，{@code styles}
 * 是本消息唯一的字典，每项 {@code [foreground, background, flags]}，按首次出现顺序收集并去重。解码要求字典恰好是 canonical first-seen
 * 顺序：重复项、未被引用的项或与首次出现顺序不一致的索引都被拒绝。颜色整数语义为 {@code -1} 缺省、 {@code -2..-257} 索引 0..255、{@code
 * 0..16777215} RGB；flags 为 {@code 0..255}。
 *
 * <p>解码前先施加顶层 UTF-8 字节上限，并在展开槽数组前按维度/历史/行替换预算校验，避免按输入放大分配。解析错误一律转为 固定描述的 {@link
 * TerminalViewUpdateException}，不携带 payload、屏幕或原始 Jackson 异常文本。
 */
public final class TerminalViewUpdateCodec {

  private static final Set<String> UPDATE_FIELDS =
      Set.of(
          "type",
          "terminalId",
          "streamId",
          "baseVersion",
          "version",
          "cols",
          "rows",
          "alternate",
          "history",
          "cursor",
          "inputModeRevision",
          "inputModes",
          "historyTrim",
          "historyAppend",
          "screenRows",
          "styles");
  private static final Set<String> CURSOR_FIELDS = Set.of("x", "y", "visible", "shape");
  private static final Set<String> INPUT_MODE_FIELDS =
      Set.of(
          "applicationCursor",
          "applicationKeypad",
          "bracketedPaste",
          "autoNewLine",
          "altSendsEscape",
          "mouseMode",
          "mouseFormat");
  private static final Set<String> SCREEN_ROW_FIELDS = Set.of("row", "line");

  private static final String PARSE_ERROR = "terminal view update must be valid strict JSON";
  private static final String INVALID_ERROR = "terminal view update is invalid";
  private static final String SIZE_ERROR =
      "terminal view update exceeds the terminal message budget";

  private static final ObjectMapper OBJECT_MAPPER =
      new ObjectMapper(
          JsonFactory.builder()
              // 解析放大防护：字符串、嵌套深度、数字长度与文档长度全部与消息预算同量级。
              .streamReadConstraints(
                  StreamReadConstraints.builder()
                      .maxStringLength(TerminalLimits.MAX_MESSAGE_BYTES)
                      .maxNestingDepth(100)
                      .maxNumberLength(1000)
                      .maxDocumentLength(TerminalLimits.MAX_MESSAGE_BYTES)
                      .build())
              .build());

  static {
    OBJECT_MAPPER.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    OBJECT_MAPPER.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
  }

  /** 将更新编码为 canonical JSON；最终 UTF-8 字节数必须落在 {@link TerminalLimits#MAX_MESSAGE_BYTES} 以内。 */
  public String encode(TerminalViewUpdate update) {
    if (update == null) {
      throw new IllegalArgumentException("update must not be null");
    }
    LinkedHashMap<TerminalView.Style, Integer> dictionary = new LinkedHashMap<>();
    for (TerminalView.Line line : update.historyAppend()) {
      registerStyles(line, dictionary);
    }
    for (TerminalViewUpdate.RowChange change : update.screenRows()) {
      registerStyles(change.line(), dictionary);
    }

    ObjectNode root = OBJECT_MAPPER.createObjectNode();
    root.put("type", update.type().name());
    root.put("terminalId", update.terminalId().toString());
    root.put("streamId", update.streamId().toString());
    if (update.baseVersion() == null) {
      root.putNull("baseVersion");
    } else {
      root.put("baseVersion", update.baseVersion());
    }
    root.put("version", update.version());
    root.put("cols", update.columns());
    root.put("rows", update.rows());
    root.put("alternate", update.alternate());
    root.put("history", update.history());

    ObjectNode cursor = root.putObject("cursor");
    cursor.put("x", update.cursorX());
    cursor.put("y", update.cursorY());
    cursor.put("visible", update.cursorVisible());
    if (update.cursorShape() == null) {
      cursor.putNull("shape");
    } else {
      cursor.put("shape", update.cursorShape().name());
    }

    root.put("inputModeRevision", update.inputModeRevision());
    ObjectNode inputModes = root.putObject("inputModes");
    inputModes.put("applicationCursor", update.inputModes().applicationCursor());
    inputModes.put("applicationKeypad", update.inputModes().applicationKeypad());
    inputModes.put("bracketedPaste", update.inputModes().bracketedPaste());
    inputModes.put("autoNewLine", update.inputModes().autoNewLine());
    inputModes.put("altSendsEscape", update.inputModes().altSendsEscape());
    inputModes.put("mouseMode", update.inputModes().mouseMode().name());
    inputModes.put("mouseFormat", update.inputModes().mouseFormat().name());

    root.put("historyTrim", update.historyTrim());
    ArrayNode historyAppend = root.putArray("historyAppend");
    for (TerminalView.Line line : update.historyAppend()) {
      historyAppend.add(encodeLine(line, dictionary));
    }
    ArrayNode screenRows = root.putArray("screenRows");
    for (TerminalViewUpdate.RowChange change : update.screenRows()) {
      ObjectNode item = screenRows.addObject();
      item.put("row", change.row());
      item.set("line", encodeLine(change.line(), dictionary));
    }
    ArrayNode styles = root.putArray("styles");
    for (TerminalView.Style style : dictionary.keySet()) {
      ArrayNode entry = styles.addArray();
      entry.add(encodeColor(style.foreground()));
      entry.add(encodeColor(style.background()));
      entry.add(encodeFlags(style));
    }

    String json = BoundedJsonWriter.write(root, TerminalLimits.MAX_MESSAGE_BYTES);
    if (json == null) {
      throw new TerminalViewUpdateException(SIZE_ERROR);
    }
    return json;
  }

  /** 解码并严格校验 canonical JSON，重建完整数值画面更新。 */
  public TerminalViewUpdate decode(String json) {
    requireBounded(json);
    JsonNode root = parse(json);
    if (!root.isObject()) {
      throw new TerminalViewUpdateException(INVALID_ERROR);
    }
    rejectUnknownFields(root, UPDATE_FIELDS);
    requireFields(root, UPDATE_FIELDS);

    TerminalViewUpdate.Kind type = decodeKind(requiredText(root, "type"));
    UUID terminalId = requiredUuid(root, "terminalId");
    UUID streamId = requiredUuid(root, "streamId");
    JsonNode baseVersionNode = root.get("baseVersion");
    Long baseVersion = baseVersionNode.isNull() ? null : requiredSafePositive(baseVersionNode);
    long version = requiredSafePositive(root.get("version"));
    int columns = requiredInt(root, "cols");
    int rows = requiredInt(root, "rows");
    boolean alternate = requiredBoolean(root, "alternate");
    int history = requiredInt(root, "history");
    if (columns < TerminalLimits.MIN_COLUMNS || columns > TerminalLimits.MAX_COLUMNS) {
      throw new TerminalViewUpdateException(INVALID_ERROR);
    }
    if (rows < TerminalLimits.MIN_ROWS || rows > TerminalLimits.MAX_ROWS) {
      throw new TerminalViewUpdateException(INVALID_ERROR);
    }
    if (history < 0 || history > TerminalLimits.MAX_HISTORY_LINES) {
      throw new TerminalViewUpdateException(INVALID_ERROR);
    }

    JsonNode cursor = requireObject(root.get("cursor"));
    rejectUnknownFields(cursor, CURSOR_FIELDS);
    requireFields(cursor, CURSOR_FIELDS);
    int cursorX = requiredInt(cursor, "x");
    int cursorY = requiredInt(cursor, "y");
    boolean cursorVisible = requiredBoolean(cursor, "visible");
    JsonNode shapeNode = cursor.get("shape");
    TerminalView.CursorShape cursorShape =
        shapeNode.isNull()
            ? null
            : enumValue(TerminalView.CursorShape.class, requireText(shapeNode));

    long inputModeRevision = requiredSafePositive(root.get("inputModeRevision"));
    TerminalView.InputModes inputModes = decodeInputModes(root.get("inputModes"));
    int historyTrim = requiredInt(root, "historyTrim");

    JsonNode historyNode = requireArray(root.get("historyAppend"));
    JsonNode screenNode = requireArray(root.get("screenRows"));
    JsonNode stylesNode = requireArray(root.get("styles"));
    // 展开任何槽之前先按维度/历史/行替换预算校验，字典大小也不得超过本消息允许的槽总量。
    if (historyNode.size() > TerminalLimits.MAX_HISTORY_LINES
        || screenNode.size() > TerminalLimits.MAX_ROWS) {
      throw new TerminalViewUpdateException(INVALID_ERROR);
    }
    long slotBudget = (long) (history + rows) * columns;
    if (stylesNode.size() > slotBudget) {
      throw new TerminalViewUpdateException(INVALID_ERROR);
    }

    StyleDictionary dictionary = new StyleDictionary(decodeStyles(stylesNode));
    List<TerminalView.Line> historyAppend = new ArrayList<>(historyNode.size());
    for (JsonNode lineNode : historyNode) {
      historyAppend.add(decodeLine(lineNode, columns, dictionary));
    }
    List<TerminalViewUpdate.RowChange> screenRows = new ArrayList<>(screenNode.size());
    for (JsonNode itemNode : screenNode) {
      JsonNode item = requireObject(itemNode);
      rejectUnknownFields(item, SCREEN_ROW_FIELDS);
      requireFields(item, SCREEN_ROW_FIELDS);
      int row = requiredInt(item, "row");
      TerminalView.Line line = decodeLine(item.get("line"), columns, dictionary);
      screenRows.add(new TerminalViewUpdate.RowChange(row, line));
    }
    dictionary.requireCanonical();

    try {
      return new TerminalViewUpdate(
          type,
          terminalId,
          streamId,
          baseVersion,
          version,
          columns,
          rows,
          alternate,
          history,
          cursorX,
          cursorY,
          cursorVisible,
          cursorShape,
          inputModeRevision,
          inputModes,
          historyTrim,
          historyAppend,
          screenRows);
    } catch (RuntimeException error) {
      throw new TerminalViewUpdateException(INVALID_ERROR);
    }
  }

  /** 顶层 UTF-8 字节上限先于解析施加；非法 Unicode 与超限都按固定协议错误拒绝。 */
  private static void requireBounded(String json) {
    if (json == null) {
      throw new TerminalViewUpdateException(INVALID_ERROR);
    }
    int bytes;
    try {
      bytes = ResourceRef.utf8LengthUpTo(json, "json", TerminalLimits.MAX_MESSAGE_BYTES);
    } catch (IllegalArgumentException error) {
      throw new TerminalViewUpdateException(INVALID_ERROR);
    }
    if (bytes > TerminalLimits.MAX_MESSAGE_BYTES) {
      throw new TerminalViewUpdateException(SIZE_ERROR);
    }
  }

  private static JsonNode parse(String json) {
    try {
      JsonNode node = OBJECT_MAPPER.readTree(json);
      if (node == null) {
        throw new TerminalViewUpdateException(INVALID_ERROR);
      }
      return node;
    } catch (JsonProcessingException error) {
      throw new TerminalViewUpdateException(PARSE_ERROR);
    }
  }

  private static TerminalViewUpdate.Kind decodeKind(String name) {
    try {
      return TerminalViewUpdate.Kind.valueOf(name);
    } catch (IllegalArgumentException error) {
      throw new TerminalViewUpdateException(INVALID_ERROR);
    }
  }

  private static TerminalView.InputModes decodeInputModes(JsonNode node) {
    JsonNode modes = requireObject(node);
    rejectUnknownFields(modes, INPUT_MODE_FIELDS);
    requireFields(modes, INPUT_MODE_FIELDS);
    return new TerminalView.InputModes(
        requiredBoolean(modes, "applicationCursor"),
        requiredBoolean(modes, "applicationKeypad"),
        requiredBoolean(modes, "bracketedPaste"),
        requiredBoolean(modes, "autoNewLine"),
        requiredBoolean(modes, "altSendsEscape"),
        enumValue(TerminalView.MouseMode.class, requiredText(modes, "mouseMode")),
        enumValue(TerminalView.MouseFormat.class, requiredText(modes, "mouseFormat")));
  }

  private static List<TerminalView.Style> decodeStyles(JsonNode node) {
    List<TerminalView.Style> styles = new ArrayList<>(node.size());
    for (JsonNode entry : node) {
      styles.add(decodeStyle(entry));
    }
    return styles;
  }

  private static TerminalView.Style decodeStyle(JsonNode entry) {
    if (!entry.isArray() || entry.size() != 3) {
      throw new TerminalViewUpdateException(INVALID_ERROR);
    }
    long foreground = requiredIntegral(entry.get(0));
    long background = requiredIntegral(entry.get(1));
    long flags = requiredIntegral(entry.get(2));
    if (flags < 0 || flags > 0xFF) {
      throw new TerminalViewUpdateException(INVALID_ERROR);
    }
    return new TerminalView.Style(
        decodeColor(foreground),
        decodeColor(background),
        (flags & 0x01) != 0,
        (flags & 0x02) != 0,
        (flags & 0x04) != 0,
        (flags & 0x08) != 0,
        (flags & 0x10) != 0,
        (flags & 0x20) != 0,
        (flags & 0x40) != 0,
        (flags & 0x80) != 0);
  }

  private static TerminalView.Color decodeColor(long value) {
    if (value == -1) {
      return null;
    }
    if (value <= -2 && value >= -257) {
      return new TerminalView.Color.Indexed((int) (-2 - value));
    }
    if (value >= 0 && value <= 0xFFFFFF) {
      int rgb = (int) value;
      return new TerminalView.Color.Rgb((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF);
    }
    throw new TerminalViewUpdateException(INVALID_ERROR);
  }

  private static TerminalView.Line decodeLine(
      JsonNode node, int columns, StyleDictionary dictionary) {
    if (node == null || !node.isArray() || node.size() != 3) {
      throw new TerminalViewUpdateException(INVALID_ERROR);
    }
    long id = requiredIntegral(node.get(0));
    if (id < 1 || id > TerminalLimits.MAX_SAFE_INTEGER) {
      throw new TerminalViewUpdateException(INVALID_ERROR);
    }
    JsonNode wrappedNode = node.get(1);
    if (!wrappedNode.isBoolean()) {
      throw new TerminalViewUpdateException(INVALID_ERROR);
    }
    JsonNode slotsNode = node.get(2);
    if (!slotsNode.isArray() || slotsNode.size() != columns) {
      throw new TerminalViewUpdateException(INVALID_ERROR);
    }
    List<TerminalView.Slot> slots = new ArrayList<>(columns);
    for (JsonNode slotNode : slotsNode) {
      slots.add(decodeSlot(slotNode, dictionary));
    }
    return new TerminalView.Line(id, wrappedNode.booleanValue(), slots);
  }

  private static TerminalView.Slot decodeSlot(JsonNode node, StyleDictionary dictionary) {
    if (node == null || !node.isArray() || node.size() != 3) {
      throw new TerminalViewUpdateException(INVALID_ERROR);
    }
    long kindValue = requiredIntegral(node.get(0));
    long code = requiredIntegral(node.get(1));
    long styleIndex = requiredIntegral(node.get(2));
    TerminalView.SlotKind kind;
    if (kindValue == 0) {
      kind = TerminalView.SlotKind.UNIT;
    } else if (kindValue == 1) {
      kind = TerminalView.SlotKind.EMPTY;
    } else if (kindValue == 2) {
      kind = TerminalView.SlotKind.DWC;
    } else {
      throw new TerminalViewUpdateException(INVALID_ERROR);
    }
    if (code < 0 || code > 0xFFFF) {
      throw new TerminalViewUpdateException(INVALID_ERROR);
    }
    if (kind == TerminalView.SlotKind.EMPTY && code != 0) {
      throw new TerminalViewUpdateException(INVALID_ERROR);
    }
    if (kind == TerminalView.SlotKind.DWC && code != 0xe000) {
      throw new TerminalViewUpdateException(INVALID_ERROR);
    }
    return new TerminalView.Slot(kind, (int) code, dictionary.resolve(styleIndex));
  }

  private static ArrayNode encodeLine(
      TerminalView.Line line, Map<TerminalView.Style, Integer> dictionary) {
    ArrayNode node = OBJECT_MAPPER.createArrayNode();
    node.add(line.id());
    node.add(line.wrapped());
    ArrayNode slots = node.addArray();
    for (TerminalView.Slot slot : line.slots()) {
      ArrayNode slotNode = slots.addArray();
      slotNode.add(encodeKind(slot.kind()));
      slotNode.add(slot.code());
      slotNode.add(dictionary.get(slot.style()).intValue());
    }
    return node;
  }

  private static int encodeKind(TerminalView.SlotKind kind) {
    return switch (kind) {
      case UNIT -> 0;
      case EMPTY -> 1;
      case DWC -> 2;
    };
  }

  private static void registerStyles(
      TerminalView.Line line, Map<TerminalView.Style, Integer> dictionary) {
    for (TerminalView.Slot slot : line.slots()) {
      dictionary.putIfAbsent(slot.style(), dictionary.size());
    }
  }

  private static int encodeColor(TerminalView.Color color) {
    if (color == null) {
      return -1;
    }
    if (color instanceof TerminalView.Color.Indexed indexed) {
      return -2 - indexed.index();
    }
    TerminalView.Color.Rgb rgb = (TerminalView.Color.Rgb) color;
    return (rgb.red() << 16) | (rgb.green() << 8) | rgb.blue();
  }

  private static int encodeFlags(TerminalView.Style style) {
    int flags = 0;
    if (style.bold()) {
      flags |= 0x01;
    }
    if (style.dim()) {
      flags |= 0x02;
    }
    if (style.italic()) {
      flags |= 0x04;
    }
    if (style.underline()) {
      flags |= 0x08;
    }
    if (style.blink()) {
      flags |= 0x10;
    }
    if (style.inverse()) {
      flags |= 0x20;
    }
    if (style.hidden()) {
      flags |= 0x40;
    }
    if (style.strikethrough()) {
      flags |= 0x80;
    }
    return flags;
  }

  private static void rejectUnknownFields(JsonNode object, Set<String> allowed) {
    var fields = object.fieldNames();
    while (fields.hasNext()) {
      if (!allowed.contains(fields.next())) {
        throw new TerminalViewUpdateException(INVALID_ERROR);
      }
    }
  }

  private static void requireFields(JsonNode object, Set<String> required) {
    for (String field : required) {
      if (!object.has(field)) {
        throw new TerminalViewUpdateException(INVALID_ERROR);
      }
    }
  }

  private static JsonNode requireObject(JsonNode node) {
    if (node == null || !node.isObject()) {
      throw new TerminalViewUpdateException(INVALID_ERROR);
    }
    return node;
  }

  private static JsonNode requireArray(JsonNode node) {
    if (node == null || !node.isArray()) {
      throw new TerminalViewUpdateException(INVALID_ERROR);
    }
    return node;
  }

  private static String requiredText(JsonNode object, String field) {
    return requireText(object.get(field));
  }

  private static String requireText(JsonNode node) {
    if (node == null || !node.isTextual()) {
      throw new TerminalViewUpdateException(INVALID_ERROR);
    }
    return node.textValue();
  }

  private static boolean requiredBoolean(JsonNode object, String field) {
    JsonNode node = object.get(field);
    if (node == null || !node.isBoolean()) {
      throw new TerminalViewUpdateException(INVALID_ERROR);
    }
    return node.booleanValue();
  }

  private static int requiredInt(JsonNode object, String field) {
    return toInt(requiredIntegral(object.get(field)));
  }

  private static int toInt(long value) {
    if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
      throw new TerminalViewUpdateException(INVALID_ERROR);
    }
    return (int) value;
  }

  private static long requiredSafePositive(JsonNode node) {
    long value = requiredIntegral(node);
    if (value < 1 || value > TerminalLimits.MAX_SAFE_INTEGER) {
      throw new TerminalViewUpdateException(INVALID_ERROR);
    }
    return value;
  }

  private static long requiredIntegral(JsonNode node) {
    if (node == null || !node.isIntegralNumber() || !node.canConvertToLong()) {
      throw new TerminalViewUpdateException(INVALID_ERROR);
    }
    return node.longValue();
  }

  private static UUID requiredUuid(JsonNode object, String field) {
    String text = requiredText(object, field);
    try {
      UUID parsed = UUID.fromString(text);
      if (!parsed.toString().equals(text)) {
        throw new IllegalArgumentException();
      }
      return parsed;
    } catch (IllegalArgumentException error) {
      throw new TerminalViewUpdateException(INVALID_ERROR);
    }
  }

  private static <E extends Enum<E>> E enumValue(Class<E> type, String name) {
    try {
      return Enum.valueOf(type, name);
    } catch (IllegalArgumentException error) {
      throw new TerminalViewUpdateException(INVALID_ERROR);
    }
  }

  /** 本消息唯一字典：按槽的 canonical first-seen 顺序解析索引，并拒绝重复、未引用或乱序字典。 */
  private static final class StyleDictionary {

    private final List<TerminalView.Style> declared;
    private final List<TerminalView.Style> canonical = new ArrayList<>();
    private final Map<TerminalView.Style, Integer> indexByStyle = new HashMap<>();

    StyleDictionary(List<TerminalView.Style> declared) {
      this.declared = declared;
    }

    TerminalView.Style resolve(long styleIndex) {
      if (styleIndex < 0 || styleIndex >= declared.size()) {
        throw new TerminalViewUpdateException(INVALID_ERROR);
      }
      TerminalView.Style style = declared.get((int) styleIndex);
      Integer existing = indexByStyle.get(style);
      int canonicalIndex;
      if (existing == null) {
        canonicalIndex = canonical.size();
        canonical.add(style);
        indexByStyle.put(style, canonicalIndex);
      } else {
        canonicalIndex = existing;
      }
      if (canonicalIndex != styleIndex) {
        throw new TerminalViewUpdateException(INVALID_ERROR);
      }
      return style;
    }

    void requireCanonical() {
      if (canonical.size() != declared.size()) {
        throw new TerminalViewUpdateException(INVALID_ERROR);
      }
    }
  }

  /** 结构化画面协议错误：消息固定，不携带 payload、屏幕或原始解析异常文本。 */
  public static final class TerminalViewUpdateException extends IllegalArgumentException {

    private TerminalViewUpdateException(String message) {
      super(message);
    }
  }
}
