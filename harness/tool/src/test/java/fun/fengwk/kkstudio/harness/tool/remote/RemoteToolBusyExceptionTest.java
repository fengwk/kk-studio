package fun.fengwk.kkstudio.harness.tool.remote;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.Test;

class RemoteToolBusyExceptionTest {

  @Test
  void preservesMessageAndOptionalCause() {
    RuntimeException cause = new RuntimeException("capacity");

    assertEquals("busy", new RemoteToolBusyException("busy").getMessage());
    RemoteToolBusyException withCause = new RemoteToolBusyException("busy", cause);
    assertEquals("busy", withCause.getMessage());
    assertSame(cause, withCause.getCause());
  }
}
