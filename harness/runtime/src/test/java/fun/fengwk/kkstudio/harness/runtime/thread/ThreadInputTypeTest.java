package fun.fengwk.kkstudio.harness.runtime.thread;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.util.List;

/** The mailbox has only response-producing message input kinds. */
class ThreadInputTypeTest {

  @Test
  void containsNoConfigurationCommands() {
    assertEquals(
        List.of("USER_MESSAGE", "CUSTOM_MESSAGE"),
        List.of(ThreadInputType.values()).stream().map(Enum::name).toList());
    for (ThreadInputType type : ThreadInputType.values()) {
      assertTrue(type.isMessage());
    }
  }
}
