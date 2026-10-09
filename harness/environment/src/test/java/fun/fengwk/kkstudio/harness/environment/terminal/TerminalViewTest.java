package fun.fengwk.kkstudio.harness.environment.terminal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.environment.terminal.TerminalView.CursorShape;
import fun.fengwk.kkstudio.harness.environment.terminal.TerminalView.InputModes;
import fun.fengwk.kkstudio.harness.environment.terminal.TerminalView.MouseFormat;
import fun.fengwk.kkstudio.harness.environment.terminal.TerminalView.MouseMode;
import fun.fengwk.kkstudio.harness.environment.terminal.TerminalView.SlotKind;

import java.util.ArrayList;
import java.util.List;

/** TerminalView 的深不可变与边界回归。 */
class TerminalViewTest {

  @Test
  void linesAndSlotsAreDefensivelyCopied() {
    List<TerminalView.Slot> slots = new ArrayList<>();
    slots.add(new TerminalView.Slot(SlotKind.UNIT, 'A', TerminalView.Style.DEFAULT));
    TerminalView.Line line = new TerminalView.Line(1L, false, slots);
    slots.clear();
    assertEquals(1L, line.id());
    assertEquals(1, line.slots().size());

    List<TerminalView.Line> lines = new ArrayList<>();
    lines.add(line);
    TerminalView view = new TerminalView(1, 1, 0, 0, false, 0, lines, true, null, 1L, modes());
    lines.clear();
    assertEquals(1, view.lines().size());
    assertEquals(1L, view.lines().get(0).id());
    assertThrows(UnsupportedOperationException.class, () -> view.lines().add(line));
    assertThrows(
        UnsupportedOperationException.class, () -> line.slots().add(TerminalView.Slot.EMPTY));
  }

  @Test
  void lineIdentityMustBePositiveAndSerializesAsLong() {
    assertThrows(IllegalArgumentException.class, () -> new TerminalView.Line(0L, false, List.of()));
    assertThrows(
        IllegalArgumentException.class, () -> new TerminalView.Line(-1L, false, List.of()));
    TerminalView.Line line = new TerminalView.Line(Long.MAX_VALUE, true, List.of());
    JsonNode encoded = new ObjectMapper().valueToTree(line);
    assertTrue(encoded.get("id").isIntegralNumber());
    assertEquals(Long.MAX_VALUE, encoded.get("id").longValue());
    assertTrue(line.wrapped());
  }

  @Test
  void slotAndInputModesRejectMissingReferences() {
    assertThrows(
        NullPointerException.class,
        () -> new TerminalView.Slot(null, (char) 0, TerminalView.Style.DEFAULT));
    assertThrows(NullPointerException.class, () -> new TerminalView.Slot(SlotKind.UNIT, 'A', null));
    assertThrows(
        NullPointerException.class,
        () -> new InputModes(false, false, false, false, false, null, MouseFormat.XTERM));
    assertThrows(
        NullPointerException.class,
        () -> new InputModes(false, false, false, false, false, MouseMode.NONE, null));
  }

  @Test
  void colorBoundsAreEnforced() {
    assertEquals(new TerminalView.Color.Indexed(0), new TerminalView.Color.Indexed(0));
    assertEquals(new TerminalView.Color.Indexed(255), new TerminalView.Color.Indexed(255));
    assertThrows(IllegalArgumentException.class, () -> new TerminalView.Color.Indexed(256));
    assertThrows(IllegalArgumentException.class, () -> new TerminalView.Color.Indexed(-1));
    assertEquals(new TerminalView.Color.Rgb(255, 0, 128), new TerminalView.Color.Rgb(255, 0, 128));
    assertThrows(IllegalArgumentException.class, () -> new TerminalView.Color.Rgb(256, 0, 0));
    assertThrows(IllegalArgumentException.class, () -> new TerminalView.Color.Rgb(0, -1, 0));
  }

  @Test
  void slotCodesSerializeAsNumbersWithoutSurrogateConversion() {
    for (int code : List.of(0, 0xd800, 0xde00, 0xfeff, 0xffff)) {
      TerminalView.Slot slot =
          new TerminalView.Slot(SlotKind.UNIT, code, TerminalView.Style.DEFAULT);
      JsonNode encoded = new ObjectMapper().valueToTree(slot);
      assertTrue(encoded.get("code").isIntegralNumber());
      assertEquals(code, encoded.get("code").intValue());
    }
    assertThrows(
        IllegalArgumentException.class,
        () -> new TerminalView.Slot(SlotKind.UNIT, -1, TerminalView.Style.DEFAULT));
    assertThrows(
        IllegalArgumentException.class,
        () -> new TerminalView.Slot(SlotKind.UNIT, 0x10000, TerminalView.Style.DEFAULT));
  }

  @Test
  void defaultSlotAndStyleAreCanonical() {
    assertEquals(SlotKind.EMPTY, TerminalView.Slot.EMPTY.kind());
    assertEquals(0, TerminalView.Slot.EMPTY.code());
    assertEquals(TerminalView.Style.DEFAULT, TerminalView.Slot.EMPTY.style());
    TerminalView.Style style = TerminalView.Style.DEFAULT;
    assertNull(style.foreground());
    assertNull(style.background());
    assertFalse(style.bold());
    assertFalse(style.dim());
    assertFalse(style.italic());
    assertFalse(style.underline());
    assertFalse(style.blink());
    assertFalse(style.inverse());
    assertFalse(style.hidden());
    assertFalse(style.strikethrough());
  }

  @Test
  void enumsExposeExpectedConstants() {
    assertEquals(3, SlotKind.values().length);
    assertEquals(6, CursorShape.values().length);
    assertEquals(6, MouseMode.values().length);
    assertEquals(4, MouseFormat.values().length);
  }

  private static InputModes modes() {
    return new InputModes(false, false, false, false, false, MouseMode.NONE, MouseFormat.XTERM);
  }
}
