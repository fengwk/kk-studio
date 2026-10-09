package fun.fengwk.kkstudio.harness.environment.terminal;

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

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** 传输 codec 测试共用的规范画面样本；确定、无 I/O，供 roundtrip 与预算测试复用。 */
final class TerminalViewSamples {

  static final UUID TERMINAL_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
  static final UUID STREAM_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

  static final Style DEFAULT = Style.DEFAULT;
  static final Style RED =
      new Style(
          new Color.Rgb(255, 0, 0), null, false, false, false, false, false, false, false, false);
  static final Style BLUE_BOLD =
      new Style(null, new Color.Indexed(4), true, false, false, false, false, false, false, false);
  static final Style ALL =
      new Style(
          new Color.Indexed(255),
          new Color.Rgb(255, 255, 255),
          true,
          true,
          true,
          true,
          true,
          true,
          true,
          true);
  static final Style GREEN =
      new Style(
          new Color.Rgb(0, 255, 0), null, false, false, false, false, false, false, false, false);

  private TerminalViewSamples() {}

  /** RESET 样本：1 行历史、2 行活动屏，覆盖缺省/索引/RGB 颜色与全部装饰位。 */
  static TerminalViewUpdate reset() {
    Line history =
        line(
            10L,
            false,
            unit(0x0041, DEFAULT),
            unit(0x0000, DEFAULT),
            unit(0xd800, RED),
            unit(0xfeff, BLUE_BOLD),
            dwc(ALL),
            empty());
    Line row0 =
        line(
            11L,
            true,
            unit(0x0042, RED),
            unit(0xffff, BLUE_BOLD),
            dwc(ALL),
            empty(),
            unit(0x0041, RED),
            unit(0x0000, BLUE_BOLD));
    Line row1 = line(12L, false, empty(), empty(), empty(), empty(), empty(), empty());
    return new TerminalViewUpdate(
        Kind.RESET,
        TERMINAL_ID,
        STREAM_ID,
        null,
        7L,
        6,
        2,
        false,
        1,
        3,
        1,
        true,
        CursorShape.BLINK_BLOCK,
        9L,
        new InputModes(true, false, true, false, true, MouseMode.BUTTON_MOTION, MouseFormat.SGR),
        0,
        List.of(history),
        List.of(new RowChange(0, row0), new RowChange(1, row1)));
  }

  /** PATCH 样本：1 行裁剪、2 行追加、1 行替换，使用 RGB 真彩与索引色两种颜色。 */
  static TerminalViewUpdate patch() {
    Line h1 = line(20L, true, unit(0x0078, GREEN), empty(), empty(), empty(), empty(), empty());
    Line h2 =
        line(21L, false, empty(), unit(0x263a, BLUE_BOLD), empty(), empty(), empty(), empty());
    Line row1 = line(22L, false, empty(), unit(0x0079, GREEN), empty(), empty(), empty(), empty());
    return new TerminalViewUpdate(
        Kind.PATCH,
        TERMINAL_ID,
        STREAM_ID,
        7L,
        8L,
        6,
        2,
        false,
        4,
        6,
        0,
        false,
        null,
        8L,
        new InputModes(false, false, false, false, false, MouseMode.NONE, MouseFormat.XTERM),
        1,
        List.of(h1, h2),
        List.of(new RowChange(1, row1)));
  }

  /** 没有行数据的 metadata-only PATCH：字典为空。 */
  static TerminalViewUpdate metadataOnlyPatch() {
    return new TerminalViewUpdate(
        Kind.PATCH,
        TERMINAL_ID,
        STREAM_ID,
        3L,
        4L,
        6,
        2,
        false,
        0,
        0,
        1,
        true,
        null,
        4L,
        new InputModes(false, false, false, false, false, MouseMode.NONE, MouseFormat.XTERM),
        0,
        List.of(),
        List.of());
  }

  /** 最大允许 RESET：512 行历史 + 100 行活动屏 × 300 列 = 183600 槽，每槽唯一 RGB/flag 组合，行 id 与版本贴近 JS 安全整数上限。 */
  static TerminalViewUpdate maximalReset() {
    int columns = TerminalLimits.MAX_COLUMNS;
    int rows = TerminalLimits.MAX_ROWS;
    int history = TerminalLimits.MAX_HISTORY_LINES;
    long maxSafe = TerminalLimits.MAX_SAFE_INTEGER;
    int totalSlots = (history + rows) * columns;
    int groups = (totalSlots + 255) / 256;
    Color background = new Color.Rgb(255, 255, 255);
    Color[] foregrounds = new Color[groups];
    for (int group = 0; group < groups; group++) {
      int rgb = 16777000 - group;
      foregrounds[group] = new Color.Rgb((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF);
    }
    long slotIndex = 0;
    long lineId = maxSafe;
    List<Line> historyLines = new ArrayList<>(history);
    for (int i = 0; i < history; i++) {
      historyLines.add(
          maximalLine(lineId--, i % 2 == 0, columns, foregrounds, background, slotIndex));
      slotIndex += columns;
    }
    List<RowChange> screenRows = new ArrayList<>(rows);
    for (int row = 0; row < rows; row++) {
      Line line = maximalLine(lineId--, false, columns, foregrounds, background, slotIndex);
      slotIndex += columns;
      screenRows.add(new RowChange(row, line));
    }
    return new TerminalViewUpdate(
        Kind.RESET,
        TERMINAL_ID,
        STREAM_ID,
        null,
        maxSafe,
        columns,
        rows,
        false,
        history,
        columns,
        rows - 1,
        true,
        CursorShape.STEADY_BLOCK,
        maxSafe,
        new InputModes(false, false, false, false, false, MouseMode.NONE, MouseFormat.XTERM),
        0,
        historyLines,
        screenRows);
  }

  private static Line maximalLine(
      long id,
      boolean wrapped,
      int columns,
      Color[] foregrounds,
      Color background,
      long firstSlotIndex) {
    List<Slot> slots = new ArrayList<>(columns);
    for (int column = 0; column < columns; column++) {
      long slotIndex = firstSlotIndex + column;
      int flags = (int) (slotIndex % 256);
      Style style =
          new Style(
              foregrounds[(int) (slotIndex / 256)],
              background,
              (flags & 0x01) != 0,
              (flags & 0x02) != 0,
              (flags & 0x04) != 0,
              (flags & 0x08) != 0,
              (flags & 0x10) != 0,
              (flags & 0x20) != 0,
              (flags & 0x40) != 0,
              (flags & 0x80) != 0);
      slots.add(new Slot(SlotKind.UNIT, 0xffff, style));
    }
    return new Line(id, wrapped, slots);
  }

  static Line line(long id, boolean wrapped, Slot... slots) {
    return new Line(id, wrapped, List.of(slots));
  }

  static Slot unit(int code, Style style) {
    return new Slot(SlotKind.UNIT, code, style);
  }

  static Slot empty() {
    return Slot.EMPTY;
  }

  static Slot dwc(Style style) {
    return new Slot(SlotKind.DWC, 0xe000, style);
  }
}
