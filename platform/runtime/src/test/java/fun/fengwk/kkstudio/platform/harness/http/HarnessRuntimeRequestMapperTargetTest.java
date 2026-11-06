package fun.fengwk.kkstudio.platform.harness.http;

import static fun.fengwk.kkstudio.platform.harness.http.HarnessRuntimeRequestMapperTestSupport.id;
import static fun.fengwk.kkstudio.platform.harness.http.HarnessRuntimeRequestMapperTestSupport.idText;
import static fun.fengwk.kkstudio.platform.harness.http.HarnessRuntimeRequestMapperTestSupport.newForkedSessionTarget;
import static fun.fengwk.kkstudio.platform.harness.http.HarnessRuntimeRequestMapperTestSupport.newSessionTarget;
import static fun.fengwk.kkstudio.platform.harness.http.HarnessRuntimeRequestMapperTestSupport.newThreadTarget;
import static fun.fengwk.kkstudio.platform.harness.http.HarnessRuntimeRequestMapperTestSupport.request;
import static fun.fengwk.kkstudio.platform.harness.http.HarnessRuntimeRequestMapperTestSupport.userCommand;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.api.AcceptCommandsCommand;
import fun.fengwk.kkstudio.harness.api.AcceptCommandsTarget;
import fun.fengwk.kkstudio.platform.harness.dto.HarnessCommandOwnerDTO;
import fun.fengwk.kkstudio.platform.harness.dto.HarnessCommandTargetDTO;
import fun.fengwk.kkstudio.platform.harness.dto.HarnessThreadCompactDTO;
import fun.fengwk.kkstudio.platform.orchestration.SessionOwner;

class HarnessRuntimeRequestMapperTargetTest {

  @Test
  void mapsOwnerAndCreationTargets() {
    // owner 身份保持不透明，展示字段不能影响授权身份。
    HarnessCommandOwnerDTO chatOwner = new HarnessCommandOwnerDTO();
    chatOwner.setKind("CHAT");
    chatOwner.setId(idText(10));
    chatOwner.setTitle("untrusted");
    chatOwner.setLink("/untrusted");
    assertEquals(
        new SessionOwner("CHAT", idText(10)), HarnessRuntimeRequestMapper.toOwner(chatOwner));

    HarnessCommandOwnerDTO issueAgentOwner = new HarnessCommandOwnerDTO();
    issueAgentOwner.setKind("CUSTOM");
    issueAgentOwner.setId("opaque:id");
    assertEquals(
        new SessionOwner("CUSTOM", "opaque:id"),
        HarnessRuntimeRequestMapper.toOwner(issueAgentOwner));

    AcceptCommandsCommand newSession =
        HarnessRuntimeRequestMapper.toAcceptCommandsCommand(
            request(newSessionTarget(), userCommand("new-session")));
    AcceptCommandsCommand newThread =
        HarnessRuntimeRequestMapper.toAcceptCommandsCommand(
            request(newThreadTarget(), userCommand("entry")));
    AcceptCommandsCommand newForkedSession =
        HarnessRuntimeRequestMapper.toAcceptCommandsCommand(
            request(newForkedSessionTarget(), userCommand("fork")));

    assertInstanceOf(AcceptCommandsTarget.NewRootSession.class, newSession.target());
    assertInstanceOf(AcceptCommandsTarget.NewThread.class, newThread.target());
    AcceptCommandsTarget.NewForkedSession forked =
        assertInstanceOf(AcceptCommandsTarget.NewForkedSession.class, newForkedSession.target());
    assertEquals(id(1), forked.sourceThreadId());
    assertEquals(id(2), forked.startEntryId());
    assertEquals(id(3), forked.sessionId());
    assertEquals(id(4), forked.threadId());
    assertFalse(forked.yoloEnabled());

    assertThrows(
        IllegalArgumentException.class,
        () -> {
          HarnessCommandOwnerDTO unknown = new HarnessCommandOwnerDTO();
          unknown.setKind("UNKNOWN");
          HarnessRuntimeRequestMapper.toOwner(unknown);
        });
    assertThrows(
        IllegalArgumentException.class,
        () -> {
          HarnessCommandOwnerDTO missingChatId = new HarnessCommandOwnerDTO();
          missingChatId.setKind("CHAT");
          HarnessRuntimeRequestMapper.toOwner(missingChatId);
        });
  }

  @Test
  void rejectsUnrepresentableTargetValues() {
    // 联合类型已保证字段集合；仍为空必填项与空白名称必须 fail closed。
    assertTargetRejected(
        new HarnessCommandTargetDTO.NewThreadDTO(idText(2), idText(3), idText(1), null, false));
    assertTargetRejected(
        new HarnessCommandTargetDTO.NewThreadDTO(idText(2), idText(3), idText(1), "   ", false));
    assertTargetRejected(
        new HarnessCommandTargetDTO.NewForkedSessionDTO(
            null, idText(2), idText(3), idText(4), false));
    assertTargetRejected(new HarnessCommandTargetDTO.NewSessionDTO(null, idText(1), null, true));
  }

  private static void assertTargetRejected(HarnessCommandTargetDTO target) {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            HarnessRuntimeRequestMapper.toAcceptCommandsCommand(
                request(target, userCommand("user"))));
  }

  @Test
  void mapperRejectsNonCanonicalIdentityAndVersion() {
    // 真实 DTO 映射必须把大写 UUID 与越界版本交给共同解析器，而不是只重复测试解析器本身。
    HarnessThreadCompactDTO uppercase = new HarnessThreadCompactDTO();
    uppercase.setExpectedVersion("1");
    assertThrows(
        IllegalArgumentException.class,
        () ->
            HarnessRuntimeRequestMapper.toCompactThreadCommand(
                "AAAAAAAA-AAAA-AAAA-AAAA-AAAAAAAAAAAA", uppercase));

    HarnessThreadCompactDTO overflow = new HarnessThreadCompactDTO();
    overflow.setExpectedVersion("9223372036854775808");
    assertThrows(
        IllegalArgumentException.class,
        () -> HarnessRuntimeRequestMapper.toCompactThreadCommand(idText(1), overflow));
  }
}
