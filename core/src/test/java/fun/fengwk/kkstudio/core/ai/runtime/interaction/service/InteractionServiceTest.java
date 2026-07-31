package fun.fengwk.kkstudio.core.ai.runtime.interaction.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.core.ai.runtime.interaction.service.impl.InteractionServiceImpl;
import fun.fengwk.kkstudio.harness.runtime.interaction.Interaction;
import fun.fengwk.kkstudio.harness.runtime.interaction.InteractionCoordinator;
import fun.fengwk.kkstudio.harness.runtime.interaction.InteractionProjection;
import fun.fengwk.kkstudio.harness.runtime.interaction.InteractionRequest;
import fun.fengwk.kkstudio.harness.runtime.interaction.InteractionStatus;
import fun.fengwk.kkstudio.share.ai.runtime.InteractionDTO;

import java.time.Instant;

/** DTO boundary exposes only the Tool invocation identity and preserves decimal validation. */
class InteractionServiceTest {
  @Test
  void getsOpenInteractionByToolInvocation() {
    InteractionCoordinator coordinator = mock(InteractionCoordinator.class);
    Interaction interaction =
        new Interaction(
            1L,
            41L,
            new InteractionRequest(
                "{\"invocationId\":41,\"threadId\":7,\"tool\":\"bash\",\"workdir\":\"/work\",\"arguments\":\"{}\"}"),
            InteractionStatus.OPEN,
            null,
            0L,
            Instant.parse("2026-01-01T00:00:00Z"),
            null);
    when(coordinator.getOpenByToolInvocation(41L))
        .thenReturn(
            new InteractionCoordinator.InteractionView(
                interaction, new InteractionProjection("{}")));

    InteractionDTO dto = new InteractionServiceImpl(coordinator).getOpenByToolInvocation("41");

    assertEquals("41", dto.getToolInvocationId());
    verify(coordinator).getOpenByToolInvocation(41L);
    assertThrows(
        IllegalArgumentException.class,
        () -> new InteractionServiceImpl(coordinator).getOpenByToolInvocation("041"));
  }
}
