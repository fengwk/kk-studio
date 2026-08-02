package fun.fengwk.kkstudio.core.ai.runtime.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import org.junit.jupiter.api.Test;
import org.springframework.context.SmartLifecycle;

import java.util.concurrent.atomic.AtomicBoolean;

class HarnessExecutionActivationConfigurationTest {

  private final HarnessExecutionActivationConfiguration configuration =
      new HarnessExecutionActivationConfiguration();

  @Test
  void lifecycleIsIdempotentAndAlwaysInvokesStopCallback() {
    PostgresqlExecutionActivationDispatcher dispatcher =
        mock(PostgresqlExecutionActivationDispatcher.class);
    PostgresqlExecutionActivationListener listener =
        mock(PostgresqlExecutionActivationListener.class);
    SmartLifecycle lifecycle =
        configuration.harnessExecutionActivationLifecycle(dispatcher, listener);

    assertFalse(lifecycle.isRunning());
    assertTrue(lifecycle.isAutoStartup());
    assertEquals(Integer.MAX_VALUE, lifecycle.getPhase());

    lifecycle.stop();
    verifyNoInteractions(dispatcher, listener);

    lifecycle.start();
    lifecycle.start();
    assertTrue(lifecycle.isRunning());
    verify(dispatcher).start();
    verify(listener).start();

    AtomicBoolean callbackInvoked = new AtomicBoolean();
    lifecycle.stop(() -> callbackInvoked.set(true));
    lifecycle.stop();

    assertFalse(lifecycle.isRunning());
    assertTrue(callbackInvoked.get());
    verify(listener).stop();
    verify(dispatcher).stop();
  }

  @Test
  void lifecycleRollsBackBothComponentsWhenStartupFails() {
    PostgresqlExecutionActivationDispatcher dispatcher =
        mock(PostgresqlExecutionActivationDispatcher.class);
    PostgresqlExecutionActivationListener listener =
        mock(PostgresqlExecutionActivationListener.class);
    IllegalStateException failure = new IllegalStateException("listener start failed");
    doThrow(failure).when(listener).start();
    SmartLifecycle lifecycle =
        configuration.harnessExecutionActivationLifecycle(dispatcher, listener);

    assertEquals(failure, assertThrows(IllegalStateException.class, lifecycle::start));

    assertFalse(lifecycle.isRunning());
    verify(dispatcher).start();
    verify(listener).start();
    verify(listener).stop();
    verify(dispatcher).stop();
  }
}
