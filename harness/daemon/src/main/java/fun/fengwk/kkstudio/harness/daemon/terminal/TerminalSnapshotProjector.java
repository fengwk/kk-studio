package fun.fengwk.kkstudio.harness.daemon.terminal;

import com.jediterm.core.Color;
import com.jediterm.terminal.CursorShape;
import com.jediterm.terminal.TerminalColor;
import com.jediterm.terminal.TerminalMode;
import com.jediterm.terminal.TextStyle;
import com.jediterm.terminal.emulator.mouse.MouseFormat;
import com.jediterm.terminal.emulator.mouse.MouseMode;
import com.jediterm.terminal.model.CharBuffer;
import com.jediterm.terminal.model.JediTerminal;
import com.jediterm.terminal.model.TerminalLine;
import com.jediterm.terminal.model.TerminalTextBuffer;
import com.jediterm.terminal.util.CharUtils;

import fun.fengwk.kkstudio.harness.environment.terminal.TerminalView;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;

/**
 * 把 JediTerm 公开缓冲一次性投影为 {@link TerminalView}。
 *
 * <p>投影逐 entry 读取 {@code TerminalLine.getEntries()/TextEntry.getText()} 的原始 UTF-16 单位与样式：{@code
 * 0xe000} 记为 DWC 续格，其余为 UNIT（含 NUL、FEFF、孤立代理项）；超出 entry 的尾槽补 {@link TerminalView.Slot#EMPTY}。投影不调用
 * NFC、码点拼接、 宽度表或字形合并，也不解释 SGR。
 *
 * <p>每个内核独占一个投影器；行身份来自实际 {@link TerminalLine} 引用而非文本匹配。成功捕获后只保留活动历史与屏幕的引用，
 * 尺寸或活动缓冲改变时清空引用但不重用行号。全部状态只在 VT owner 与缓冲锁下访问。
 */
final class TerminalSnapshotProjector {

  private IdentityHashMap<TerminalLine, Long> lineIds = new IdentityHashMap<>();
  private long nextLineId = 1L;
  private int lastColumns;
  private int lastRows;
  private boolean lastAlternate;

  TerminalSnapshotProjector() {}

  /** 在 VT owner 上加锁投影当前画面；{@code history} 在备用缓冲下恒为 0。 */
  TerminalView project(
      JediTerminal terminal,
      TerminalTextBuffer buffer,
      HeadlessTerminalDisplay display,
      long inputModeRevision) {
    buffer.lock();
    try {
      resetIfChanged(buffer);
      int columns = buffer.getWidth();
      int rows = buffer.getHeight();
      int history = buffer.isUsingAlternateBuffer() ? 0 : buffer.getHistoryLinesCount();
      IdentityHashMap<TerminalLine, Long> captured = new IdentityHashMap<>();
      List<TerminalView.Line> lines = new ArrayList<>(history + rows);
      for (int row = -history; row < rows; row++) {
        TerminalLine line = buffer.getLine(row);
        Long id = lineIds.get(line);
        if (id == null) {
          id = nextLineId;
          nextLineId = Math.incrementExact(nextLineId);
        }
        captured.put(line, id);
        lines.add(projectLine(line, columns, id));
      }
      TerminalView view =
          new TerminalView(
              columns,
              rows,
              terminal.getCursorX() - 1,
              terminal.getCursorY() - 1,
              buffer.isUsingAlternateBuffer(),
              history,
              lines,
              display.cursorVisible(),
              mapCursorShape(display.cursorShape()),
              inputModeRevision,
              inputModes(terminal, display));
      // 仅在完整投影成功后替换引用集合，不保留已裁剪历史或隐藏缓冲。
      lineIds = captured;
      return view;
    } finally {
      buffer.unlock();
    }
  }

  /** owner 在缓冲锁下观察每次解释/resize 的实际状态，也覆盖两次捕获间切出再切回的情况。 */
  void resetIfChanged(TerminalTextBuffer buffer) {
    int columns = buffer.getWidth();
    int rows = buffer.getHeight();
    boolean alternate = buffer.isUsingAlternateBuffer();
    if (columns != lastColumns || rows != lastRows || alternate != lastAlternate) {
      lineIds.clear();
      lastColumns = columns;
      lastRows = rows;
      lastAlternate = alternate;
    }
  }

  /** 仅供包内测试检查捕获后的引用预算，不暴露行引用。 */
  int retainedLineCount() {
    return lineIds.size();
  }

  /** 投影单行；每行恰好 {@code columns} 个槽，超出部分裁掉。 */
  static TerminalView.Line projectLine(TerminalLine line, int columns, long id) {
    List<TerminalView.Slot> slots = new ArrayList<>(columns);
    for (TerminalLine.TextEntry entry : line.getEntries()) {
      TerminalView.Style style = projectStyle(entry.getStyle());
      CharBuffer text = entry.getText();
      int length = text.length();
      for (int index = 0; index < length; index++) {
        if (slots.size() >= columns) {
          return new TerminalView.Line(id, line.isWrapped(), slots);
        }
        char code = text.charAt(index);
        TerminalView.SlotKind kind =
            code == CharUtils.DWC ? TerminalView.SlotKind.DWC : TerminalView.SlotKind.UNIT;
        slots.add(new TerminalView.Slot(kind, code, style));
      }
    }
    while (slots.size() < columns) {
      slots.add(TerminalView.Slot.EMPTY);
    }
    return new TerminalView.Line(id, line.isWrapped(), slots);
  }

  /** 投影逐槽样式；JediTerm 3.76 无 strikethrough，固定为 false。 */
  static TerminalView.Style projectStyle(TextStyle style) {
    if (style == null) {
      return TerminalView.Style.DEFAULT;
    }
    return new TerminalView.Style(
        projectColor(style.getForeground()),
        projectColor(style.getBackground()),
        style.hasOption(TextStyle.Option.BOLD),
        style.hasOption(TextStyle.Option.DIM),
        style.hasOption(TextStyle.Option.ITALIC),
        style.hasOption(TextStyle.Option.UNDERLINED),
        style.hasOption(TextStyle.Option.SLOW_BLINK)
            || style.hasOption(TextStyle.Option.RAPID_BLINK),
        style.hasOption(TextStyle.Option.INVERSE),
        style.hasOption(TextStyle.Option.HIDDEN),
        false);
  }

  private static TerminalView.Color projectColor(TerminalColor color) {
    if (color == null) {
      return null;
    }
    if (color.isIndexed()) {
      return new TerminalView.Color.Indexed(color.getColorIndex());
    }
    Color rgb = color.toColor();
    return new TerminalView.Color.Rgb(rgb.getRed(), rgb.getGreen(), rgb.getBlue());
  }

  static TerminalView.InputModes inputModes(
      JediTerminal terminal, HeadlessTerminalDisplay display) {
    return new TerminalView.InputModes(
        terminal.isModelEnabled(TerminalMode.CursorKey),
        terminal.isModelEnabled(TerminalMode.Keypad),
        terminal.isModelEnabled(TerminalMode.BracketedPasteMode),
        terminal.isAutoNewLine(),
        terminal.isModelEnabled(TerminalMode.AltSendsEscape),
        mapMouseMode(display.mouseMode()),
        mapMouseFormat(display.mouseFormat()));
  }

  private static TerminalView.MouseMode mapMouseMode(MouseMode mouseMode) {
    return switch (mouseMode) {
      case MOUSE_REPORTING_NONE -> TerminalView.MouseMode.NONE;
      case MOUSE_REPORTING_NORMAL -> TerminalView.MouseMode.NORMAL;
      case MOUSE_REPORTING_HILITE -> TerminalView.MouseMode.HILITE;
      case MOUSE_REPORTING_BUTTON_MOTION -> TerminalView.MouseMode.BUTTON_MOTION;
      case MOUSE_REPORTING_ALL_MOTION -> TerminalView.MouseMode.ALL_MOTION;
      case MOUSE_REPORTING_FOCUS -> TerminalView.MouseMode.FOCUS;
    };
  }

  private static TerminalView.MouseFormat mapMouseFormat(MouseFormat mouseFormat) {
    return switch (mouseFormat) {
      case MOUSE_FORMAT_XTERM_EXT -> TerminalView.MouseFormat.XTERM_EXT;
      case MOUSE_FORMAT_URXVT -> TerminalView.MouseFormat.URXVT;
      case MOUSE_FORMAT_SGR -> TerminalView.MouseFormat.SGR;
      case MOUSE_FORMAT_XTERM -> TerminalView.MouseFormat.XTERM;
    };
  }

  private static TerminalView.CursorShape mapCursorShape(CursorShape cursorShape) {
    if (cursorShape == null) {
      return null;
    }
    return switch (cursorShape) {
      case BLINK_BLOCK -> TerminalView.CursorShape.BLINK_BLOCK;
      case STEADY_BLOCK -> TerminalView.CursorShape.STEADY_BLOCK;
      case BLINK_UNDERLINE -> TerminalView.CursorShape.BLINK_UNDERLINE;
      case STEADY_UNDERLINE -> TerminalView.CursorShape.STEADY_UNDERLINE;
      case BLINK_VERTICAL_BAR -> TerminalView.CursorShape.BLINK_VERTICAL_BAR;
      case STEADY_VERTICAL_BAR -> TerminalView.CursorShape.STEADY_VERTICAL_BAR;
    };
  }
}
