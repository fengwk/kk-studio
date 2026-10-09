package fun.fengwk.kkstudio.harness.environment.terminal;

import java.util.List;
import java.util.Objects;

/**
 * 终端画面的深不可变数值投影。
 *
 * <p>权威数据是逐槽 numeric UTF-16 code unit、槽 kind（{@link SlotKind#UNIT} / {@link SlotKind#EMPTY} /
 * {@link SlotKind#DWC}）、逐槽样式与行 wrapped 状态。本模型不做 Unicode 宽度推理、码点拼接、字形合并或规范化：宽字符是否占两格只能由后续 {@link
 * SlotKind#DWC} 续格证明。每行恰好 {@link #columns} 个槽，渲染侧据实际槽拓扑裁剪。
 *
 * <p>本类仅依赖 JDK，不依赖任何终端模拟器实现；{@code harness-daemon} 的内核把 JediTerm 公开缓冲一次性地投影为本模型（真实缓冲本身的历史
 * 上限即投影的历史上限，不另存无限历史或身份映射）。
 */
public record TerminalView(
    int columns,
    int rows,
    int cursorX,
    int cursorY,
    boolean alternate,
    int history,
    List<Line> lines,
    boolean cursorVisible,
    CursorShape cursorShape,
    long inputModeRevision,
    InputModes inputModes) {

  public TerminalView {
    lines = List.copyOf(lines);
    Objects.requireNonNull(inputModes, "inputModes");
  }

  /** 一行：{@code wrapped} 加恰好 {@link TerminalView#columns} 个槽。 */
  public record Line(boolean wrapped, List<Slot> slots) {

    public Line {
      slots = List.copyOf(slots);
    }
  }

  /** 槽类型；缺失尾槽是 {@link #EMPTY}，JediTerm 双宽续格（{@code 0xe000}）是 {@link #DWC}。 */
  public enum SlotKind {
    UNIT,
    EMPTY,
    DWC
  }

  /** 单个单元格槽；{@code code} 始终是该槽的 UTF-16 code unit，且每个槽独立携带不可变样式。 */
  public record Slot(SlotKind kind, int code, Style style) {

    /** 缺失尾槽：{@code code=0} 且使用缺省样式。 */
    public static final Slot EMPTY = new Slot(SlotKind.EMPTY, 0, Style.DEFAULT);

    public Slot {
      Objects.requireNonNull(kind, "kind");
      Objects.requireNonNull(style, "style");
      if (code < 0 || code > 0xffff) {
        throw new IllegalArgumentException("slot code must be a UTF-16 unit");
      }
    }
  }

  /** 前景/背景颜色：真实索引色（0..255）或 RGB 三分量；无颜色用 {@code null} 表示。 */
  public sealed interface Color permits Color.Indexed, Color.Rgb {

    /** 调色板索引颜色。 */
    record Indexed(int index) implements Color {

      public Indexed {
        if (index < 0 || index > 255) {
          throw new IllegalArgumentException("indexed color out of range: " + index);
        }
      }
    }

    /** 真彩颜色。 */
    record Rgb(int red, int green, int blue) implements Color {

      public Rgb {
        requireChannel(red);
        requireChannel(green);
        requireChannel(blue);
      }

      private static void requireChannel(int value) {
        if (value < 0 || value > 255) {
          throw new IllegalArgumentException("rgb channel out of range: " + value);
        }
      }
    }
  }

  /** 逐槽样式；颜色 {@code null} 表示容器默认值，布尔位均为显式独立字段。 */
  public record Style(
      Color foreground,
      Color background,
      boolean bold,
      boolean dim,
      boolean italic,
      boolean underline,
      boolean blink,
      boolean inverse,
      boolean hidden,
      boolean strikethrough) {

    /** 缺省样式：默认前景/背景、无装饰。 */
    public static final Style DEFAULT =
        new Style(null, null, false, false, false, false, false, false, false, false);
  }

  /** 输入模式快照，只由公开 API 与 Display 回调得到的权威状态构成。 */
  public record InputModes(
      boolean applicationCursor,
      boolean applicationKeypad,
      boolean bracketedPaste,
      boolean autoNewLine,
      boolean altSendsEscape,
      MouseMode mouseMode,
      MouseFormat mouseFormat) {

    public InputModes {
      Objects.requireNonNull(mouseMode, "mouseMode");
      Objects.requireNonNull(mouseFormat, "mouseFormat");
    }
  }

  /** 鼠标上报模式。 */
  public enum MouseMode {
    NONE,
    NORMAL,
    HILITE,
    BUTTON_MOTION,
    ALL_MOTION,
    FOCUS
  }

  /** 鼠标上报格式。 */
  public enum MouseFormat {
    XTERM_EXT,
    URXVT,
    SGR,
    XTERM
  }

  /** 光标形状；{@code null} 表示默认形状。 */
  public enum CursorShape {
    BLINK_BLOCK,
    STEADY_BLOCK,
    BLINK_UNDERLINE,
    STEADY_UNDERLINE,
    BLINK_VERTICAL_BAR,
    STEADY_VERTICAL_BAR
  }
}
