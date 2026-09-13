package fun.fengwk.kkstudio.web.runtime;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.harness.environment.server.EnvironmentSessionListener;
import fun.fengwk.kkstudio.harness.infra.dispatch.HarnessWorkDispatcher;
import fun.fengwk.kkstudio.platform.environment.operation.EnvironmentOperationDispatcher;

import java.util.UUID;
import java.util.function.Consumer;

/**
 * 意图：验证 compositeEnvironmentSessionListener 的分发与异常隔离契约： READY 事件必须同时唤醒
 * EnvironmentOperationDispatcher 与 HarnessWorkDispatcher； 任意一方抛出运行时异常，均不得中断另一方的唤醒，且不得向调用方泄露异常。
 */
class CompositeEnvironmentSessionListenerTest {

  private EnvironmentOperationDispatcher operationDispatcher;
  private ObjectProvider<EnvironmentOperationDispatcher> operationDispatcherProvider;
  private HarnessWorkDispatcher workDispatcher;
  private ObjectProvider<HarnessWorkDispatcher> dispatcherProvider;
  private EnvironmentSessionListener listener;

  @SuppressWarnings("unchecked")
  @BeforeEach
  void setUp() {
    operationDispatcher = mock(EnvironmentOperationDispatcher.class);
    operationDispatcherProvider = mock(ObjectProvider.class);
    doAnswer(
            invocation -> {
              Consumer<EnvironmentOperationDispatcher> consumer = invocation.getArgument(0);
              consumer.accept(operationDispatcher);
              return null;
            })
        .when(operationDispatcherProvider)
        .ifAvailable(any());

    workDispatcher = mock(HarnessWorkDispatcher.class);
    dispatcherProvider = mock(ObjectProvider.class);
    doAnswer(
            invocation -> {
              Consumer<HarnessWorkDispatcher> consumer = invocation.getArgument(0);
              consumer.accept(workDispatcher);
              return null;
            })
        .when(dispatcherProvider)
        .ifAvailable(any());

    HarnessRuntimeConfiguration config = new HarnessRuntimeConfiguration();
    listener =
        config.compositeEnvironmentSessionListener(operationDispatcherProvider, dispatcherProvider);
  }

  @Test
  void wakesBothDispatchers() {
    EnvironmentId envId = EnvironmentId.of(UUID.randomUUID());
    assertDoesNotThrow(() -> listener.onEnvironmentReady(envId));

    verify(operationDispatcher).wake();
    verify(workDispatcher).wake();
  }

  @Test
  void operationDispatcherExceptionDoesNotBlockWorkDispatcher() {
    doThrow(new IllegalStateException("op wake failed")).when(operationDispatcher).wake();

    EnvironmentId envId = EnvironmentId.of(UUID.randomUUID());
    assertDoesNotThrow(() -> listener.onEnvironmentReady(envId));

    verify(operationDispatcher).wake();
    verify(workDispatcher).wake();
  }

  @Test
  void workDispatcherExceptionDoesNotPropagate() {
    doThrow(new IllegalStateException("work wake failed")).when(workDispatcher).wake();

    EnvironmentId envId = EnvironmentId.of(UUID.randomUUID());
    assertDoesNotThrow(() -> listener.onEnvironmentReady(envId));

    verify(operationDispatcher).wake();
    verify(workDispatcher).wake();
  }
}
