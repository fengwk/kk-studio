package fun.fengwk.kkstudio.web.runtime;

import static fun.fengwk.kkstudio.web.runtime.HarnessRuntimeRequestMapperTestSupport.assertTargetRejected;
import static fun.fengwk.kkstudio.web.runtime.HarnessRuntimeRequestMapperTestSupport.branchSettings;
import static fun.fengwk.kkstudio.web.runtime.HarnessRuntimeRequestMapperTestSupport.id;
import static fun.fengwk.kkstudio.web.runtime.HarnessRuntimeRequestMapperTestSupport.idText;
import static fun.fengwk.kkstudio.web.runtime.HarnessRuntimeRequestMapperTestSupport.newForkedSessionTarget;
import static fun.fengwk.kkstudio.web.runtime.HarnessRuntimeRequestMapperTestSupport.newSessionTarget;
import static fun.fengwk.kkstudio.web.runtime.HarnessRuntimeRequestMapperTestSupport.newThreadTarget;
import static fun.fengwk.kkstudio.web.runtime.HarnessRuntimeRequestMapperTestSupport.request;
import static fun.fengwk.kkstudio.web.runtime.HarnessRuntimeRequestMapperTestSupport.userCommand;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.api.AcceptCommandsCommand;
import fun.fengwk.kkstudio.harness.api.AcceptCommandsTarget;
import fun.fengwk.kkstudio.platform.orchestration.SessionOwner;
import fun.fengwk.kkstudio.share.runtime.HarnessCommandOwnerDTO;
import fun.fengwk.kkstudio.share.runtime.HarnessCommandTargetDTO;
import fun.fengwk.kkstudio.share.runtime.HarnessThreadCompactDTO;

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

    // 既有 Thread 不经由 target union 承载：type "THREAD" 按未知 target 类型拒绝。
    HarnessCommandTargetDTO thread = new HarnessCommandTargetDTO();
    thread.setType("THREAD");
    thread.setThreadId(idText(1));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            HarnessRuntimeRequestMapper.toAcceptCommandsCommand(
                request(thread, userCommand("thread"))));

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
  void rejectsTargetUnionViolationsAndUnknownType() {
    // NEW_SESSION/NEW_THREAD 的字段集合互斥；THREAD 与未知 target 类型一律拒绝。
    HarnessCommandTargetDTO newSession = newSessionTarget();
    newSession.setStartEntryId(idText(8));
    assertTargetRejected(newSession);

    HarnessCommandTargetDTO newThread = newThreadTarget();
    newThread.setRootSettings(branchSettings());
    assertTargetRejected(newThread);

    // threadName 是 NEW_THREAD 必填：缺失或空白都拒绝；且 NEW_SESSION 不得携带 threadName。
    HarnessCommandTargetDTO missingThreadName = newThreadTarget();
    missingThreadName.setThreadName(null);
    assertTargetRejected(missingThreadName);
    HarnessCommandTargetDTO blankThreadName = newThreadTarget();
    blankThreadName.setThreadName("   ");
    assertTargetRejected(blankThreadName);
    HarnessCommandTargetDTO sessionWithThreadName = newSessionTarget();
    sessionWithThreadName.setThreadName("branch");
    assertTargetRejected(sessionWithThreadName);

    // NEW_FORKED_SESSION 的字段集合互斥：rootSettings / threadName 禁用，sourceThreadId 必填。
    HarnessCommandTargetDTO forkedWithRootSettings = newForkedSessionTarget();
    forkedWithRootSettings.setRootSettings(branchSettings());
    assertTargetRejected(forkedWithRootSettings);

    HarnessCommandTargetDTO forkedWithThreadName = newForkedSessionTarget();
    forkedWithThreadName.setThreadName("branch");
    assertTargetRejected(forkedWithThreadName);

    HarnessCommandTargetDTO missingSourceThreadId = newForkedSessionTarget();
    missingSourceThreadId.setSourceThreadId(null);
    assertTargetRejected(missingSourceThreadId);

    // sourceThreadId 只属于 NEW_FORKED_SESSION。
    HarnessCommandTargetDTO newSessionWithSource = newSessionTarget();
    newSessionWithSource.setSourceThreadId(idText(7));
    assertTargetRejected(newSessionWithSource);
    HarnessCommandTargetDTO newThreadWithSource = newThreadTarget();
    newThreadWithSource.setSourceThreadId(idText(7));
    assertTargetRejected(newThreadWithSource);
    // 显式 null 也按 presence 拒绝：不能靠 null 绕过禁用字段校验。
    HarnessCommandTargetDTO newSessionWithNullSource = newSessionTarget();
    newSessionWithNullSource.setSourceThreadId(null);
    assertTargetRejected(newSessionWithNullSource);

    HarnessCommandTargetDTO forkedMissingYolo = newForkedSessionTarget();
    forkedMissingYolo.setYoloEnabled(null);
    assertTargetRejected(forkedMissingYolo);

    HarnessCommandTargetDTO thread = new HarnessCommandTargetDTO();
    thread.setType("THREAD");
    thread.setThreadId(idText(1));
    assertTargetRejected(thread);

    HarnessCommandTargetDTO unknown = new HarnessCommandTargetDTO();
    unknown.setType("UNKNOWN");
    assertTargetRejected(unknown);

    HarnessCommandTargetDTO missingYolo = new HarnessCommandTargetDTO();
    missingYolo.setType("NEW_SESSION");
    missingYolo.setSessionId(idText(2));
    missingYolo.setThreadId(idText(1));
    missingYolo.setRootSettings(branchSettings());
    assertTargetRejected(missingYolo);
  }

  @Test
  void mapperRejectsNonCanonicalIdentityAndVersion() {
    // 测试意图：真实 DTO 映射必须把大写 UUID 与越界版本交给共同解析器，而不是只重复测试解析器本身。
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
