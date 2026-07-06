package fun.fengwk.kkstudio.core.agent.session.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import fun.fengwk.kkstudio.core.agent.definition.service.model.AgentDefinition;
import fun.fengwk.kkstudio.core.agent.session.service.model.AgentSession;
import fun.fengwk.kkstudio.core.agent.session.service.model.AgentSessionEvent;
import fun.fengwk.kkstudio.core.agent.session.service.model.AgentSessionHead;
import fun.fengwk.kkstudio.share.model.AgentSessionCreateDTO;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import fun.fengwk.kkstudio.core.agent.definition.service.model.AgentDefinition;
import fun.fengwk.kkstudio.core.agent.session.service.model.AgentSession;
import fun.fengwk.kkstudio.core.agent.session.service.model.AgentSessionEvent;
import fun.fengwk.kkstudio.core.agent.session.service.model.AgentSessionHead;
import fun.fengwk.kkstudio.share.model.AgentSessionCreateDTO;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

/**
 * AgentSessionMutationFactory 的聚焦行为测试。
 *
 * @author fengwk
 */
public class AgentSessionMutationFactoryTest {

  /** 校验会话创建对象会继承 agent 基本信息，并带上默认的 root head 状态。 */
  @Test
  public void shouldCreateSessionWithDefaultRootState() {
    AgentSessionMutationFactory factory = new AgentSessionMutationFactory(new ObjectMapper());
    AgentDefinition agent = new AgentDefinition();
    agent.setId(1L);
    agent.setName("default-assistant");
    AgentSessionCreateDTO createDTO = new AgentSessionCreateDTO();
    createDTO.setTitle("Bootstrap Session");
    LocalDateTime now = LocalDateTime.of(2026, 1, 1, 10, 0, 0);

    AgentSession session = factory.newSession(agent, createDTO, now);

    assertNotNull(session.getId());
    assertNotNull(session.getSessionId());
    assertEquals(1L, session.getAgentId());
    assertEquals("default-assistant", session.getAgentName());
    assertEquals("Bootstrap Session", session.getTitle());
    assertEquals("active", session.getStatus());
    assertEquals("root", session.getCurrentHeadEventId());
    assertEquals(now, session.getCreateTime());
    assertEquals(now, session.getUpdateTime());
  }

  /** 校验默认 head 会绑定到目标 session，并固定从 root 开始。 */
  @Test
  public void shouldCreateDefaultHeadForSession() {
    AgentSessionMutationFactory factory = new AgentSessionMutationFactory(new ObjectMapper());

    AgentSessionHead head = factory.newDefaultHead("se_1");

    assertNotNull(head.getId());
    assertNotNull(head.getHeadId());
    assertEquals("se_1", head.getSessionId());
    assertEquals("default", head.getHeadName());
    assertEquals("root", head.getHeadEventId());
  }

  /** 校验用户消息事件会序列化文本 payload，并保留 run 与父事件关系。 */
  @Test
  public void shouldCreateUserMessageEventWithSerializedPayload() {
    AgentSessionMutationFactory factory = new AgentSessionMutationFactory(new ObjectMapper());
    LocalDateTime now = LocalDateTime.of(2026, 1, 1, 10, 1, 0);

    AgentSessionEvent event =
        factory.newUserMessageEvent("se_1", "ev_parent", "rn_1", "Hello kk-studio", now);

    assertNotNull(event.getId());
    assertNotNull(event.getEventId());
    assertEquals("se_1", event.getSessionId());
    assertEquals("ev_parent", event.getParentEventId());
    assertEquals("rn_1", event.getRunId());
    assertEquals("user_message", event.getEventType());
    assertEquals("text", event.getPayloadType());
    assertEquals("{\"content\":\"Hello kk-studio\"}", event.getPayloadJson());
    assertEquals(now, event.getCreateTime());
  }

  /** 校验关键构造与调用入参不能为空，避免生成半残的持久化对象。 */
  @Test
  public void shouldRejectInvalidArguments() {
    ObjectMapper objectMapper = new ObjectMapper();
    AgentSessionMutationFactory factory = new AgentSessionMutationFactory(objectMapper);

    assertThrows(IllegalArgumentException.class, () -> new AgentSessionMutationFactory(null));
    assertThrows(
        IllegalArgumentException.class,
        () -> factory.newSession(null, new AgentSessionCreateDTO(), LocalDateTime.now()));
    assertThrows(
        IllegalArgumentException.class,
        () -> factory.newSession(new AgentDefinition(), null, LocalDateTime.now()));
    assertThrows(
        IllegalArgumentException.class,
        () -> factory.newSession(new AgentDefinition(), new AgentSessionCreateDTO(), null));
    assertThrows(IllegalArgumentException.class, () -> factory.newDefaultHead(" "));
    assertThrows(
        IllegalArgumentException.class,
        () -> factory.newUserMessageEvent(" ", "ev_parent", "rn_1", "hi", LocalDateTime.now()));
    assertThrows(
        IllegalArgumentException.class,
        () -> factory.newUserMessageEvent("se_1", " ", "rn_1", "hi", LocalDateTime.now()));
    assertThrows(
        IllegalArgumentException.class,
        () -> factory.newUserMessageEvent("se_1", "ev_parent", " ", "hi", LocalDateTime.now()));
    assertThrows(
        IllegalArgumentException.class,
        () -> factory.newUserMessageEvent("se_1", "ev_parent", "rn_1", null, LocalDateTime.now()));
    assertThrows(
        IllegalArgumentException.class,
        () -> factory.newUserMessageEvent("se_1", "ev_parent", "rn_1", "hi", null));
  }
}
