package fun.fengwk.kkstudio.core.agent.runtime.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import fun.fengwk.kkstudio.core.CoreTestApplication;
import fun.fengwk.kkstudio.core.agent.session.service.AgentSessionService;
import fun.fengwk.kkstudio.share.model.AgentSessionCreateDTO;
import fun.fengwk.kkstudio.share.model.AgentSessionDTO;

/**
 * EmbeddedAgentRuntimeReferenceResolver 的聚焦行为测试。
 *
 * @author fengwk
 */
@SpringBootTest(classes = CoreTestApplication.class)
public class EmbeddedAgentRuntimeReferenceResolverTest {

  @Autowired private EmbeddedAgentRuntimeReferenceResolver runtimeReferenceResolver;

  @Autowired private AgentSessionService agentSessionService;

  /** 校验 resolver 会沿 session 一次性解出 runtime 所需的 agent/provider/model 引用。 */
  @Test
  public void shouldResolveRuntimeReferencesFromSession() {
    AgentSessionCreateDTO createDTO = new AgentSessionCreateDTO();
    createDTO.setAgentName("default-assistant");
    createDTO.setTitle("Runtime Resolver Session");
    AgentSessionDTO session = agentSessionService.createSession(createDTO);

    EmbeddedAgentRuntimeReferenceResolver.RuntimeReferences references =
        runtimeReferenceResolver.resolve(session.getSessionId());

    assertEquals(session.getSessionId(), references.session().getSessionId());
    assertEquals(session.getAgentId(), references.agentDefinition().getId());
    assertNotNull(references.provider());
    assertNotNull(references.model());
    assertEquals(
        references.agentDefinition().getDefaultProviderId(), references.provider().getId());
    assertEquals(references.agentDefinition().getDefaultModelId(), references.model().getId());
  }

  /** 校验缺失 session 时会直接失败，避免 runtime 在半解析状态下继续启动。 */
  @Test
  public void shouldRejectMissingSession() {
    assertThrows(
        IllegalArgumentException.class, () -> runtimeReferenceResolver.resolve("se_missing"));
  }
}
