package fun.fengwk.kkstudio.core.agent.runtime.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import fun.fengwk.kkstudio.core.CoreTestApplication;
import fun.fengwk.kkstudio.core.agent.session.service.AgentSessionService;
import fun.fengwk.kkstudio.share.model.AgentSessionCreateDTO;
import fun.fengwk.kkstudio.share.model.AgentSessionDTO;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/** Ensures the legacy runtime resolves its global model and provider references. */
@SpringBootTest(classes = CoreTestApplication.class)
public class EmbeddedAgentRuntimeReferenceResolverTest {

  @Autowired private EmbeddedAgentRuntimeReferenceResolver runtimeReferenceResolver;
  @Autowired private AgentSessionService agentSessionService;

  @Test
  public void shouldResolveRuntimeReferences() {
    AgentSessionCreateDTO request = new AgentSessionCreateDTO();
    request.setAgentName("default-assistant");
    AgentSessionDTO session = agentSessionService.createSession(request);

    EmbeddedAgentRuntimeReferenceResolver.RuntimeReferences references =
        runtimeReferenceResolver.resolve(session.getSessionId());

    assertEquals(session.getAgentId(), references.agentDefinition().getId());
    assertEquals(references.agentDefinition().getModelId(), references.model().getId());
    assertEquals(references.model().getProviderId(), references.provider().getId());
    assertNotNull(references.provider());
  }

  @Test
  public void shouldRejectMissingSession() {
    assertThrows(
        IllegalArgumentException.class, () -> runtimeReferenceResolver.resolve("se_missing"));
  }
}
