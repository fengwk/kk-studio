package fun.fengwk.kkstudio.harness.tool.daemon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import java.util.List;

class DaemonSkillsCodecTest {

  private final DaemonSkillsCodec codec = new DaemonSkillsCodec();

  @Test
  void roundTripsReadySkills() {
    List<DaemonSkillDescriptor> original =
        List.of(
            new DaemonSkillDescriptor("alpha", "Alpha skill"),
            new DaemonSkillDescriptor("beta", "Beta skill"));

    assertEquals(original, codec.decode(codec.encode(original)));
  }

  @Test
  void rejectsDuplicateSkills() {
    assertThrows(
        DaemonProtocolException.class,
        () ->
            codec.encode(
                List.of(
                    new DaemonSkillDescriptor("same", "one"),
                    new DaemonSkillDescriptor("same", "two"))));
  }

  @Test
  void rejectsUnknownFields() {
    assertThrows(DaemonProtocolException.class, () -> codec.decode("{\"skills\":[],\"tools\":[]}"));
  }
}
