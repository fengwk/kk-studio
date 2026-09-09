package fun.fengwk.kkstudio.harness.runtime;

import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.T0;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.Baseline;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.session.Session;
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.runtime.store.testing.InMemoryHarnessStore;
import fun.fengwk.kkstudio.harness.runtime.store.testing.TestIds;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;
import fun.fengwk.kkstudio.harness.runtime.thread.command.CustomMessageCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.thread.command.NewThreadCommand;

import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/** 初始 Session / Thread 名称的服务端派生规则与 exact replay 后的名称保持。 */
class HarnessNamesTest {

  private InMemoryHarnessStore store;
  private HarnessRuntime runtime;

  @BeforeEach
  void setUp() {
    store = new InMemoryHarnessStore();
    runtime = HarnessRuntimeTestSupport.runtime(store, Clock.fixed(T0, ZoneOffset.UTC));
  }

  private static AcceptCommandsCommand newSession(
      UUID sessionId, UUID threadId, List<NewThreadCommand> commands) {
    return new AcceptCommandsCommand(
        new AcceptCommandsTarget.NewSession(
            sessionId, threadId, HarnessRuntimeTestSupport.settings(), null, false),
        commands);
  }

  private static NewThreadCommand userMessage(String text) {
    return HarnessRuntimeTestSupport.userMessageCommand(UUID.randomUUID(), text);
  }

  /** 由首条 user-like 消息构造：可拼接自定义 contents（不直接暴露 builder，用二参构造的用户消息即可）。 */
  private static NewThreadCommand customUserMessage(String text) {
    return new NewThreadCommand(
        new CustomMessageCommandPayload(
            new AgentMessage(AgentMessageRole.USER, List.of(new TextMessageContent(text)))),
        UUID.randomUUID());
  }

  @Test
  void newSessionNameComesFromFirstUserTextNormalizedToOneLine() {
    // Session 名来自初始末尾 user-like message 的首个非空文本：折叠为单行，不带省略号。
    UUID sessionId = TestIds.id(101);
    UUID threadId = TestIds.id(102);
    String messy = "  第一条  消息 \n 继续 说 ";
    AcceptedCommands accepted =
        runtime.acceptCommands(
            newSession(sessionId, threadId, List.of(userMessage(messy))),
            AcceptancePreflight.IDENTITY);
    assertEquals("第一条 消息 继续 说", accepted.session().name());
    Session stored = store.transaction(tx -> tx.findSession(sessionId).orElseThrow());
    assertEquals("第一条 消息 继续 说", stored.name());
  }

  @Test
  void sessionNameTextIsTruncatedToFortyCodePointsWithoutEllipsis() {
    // 超长文本只保留前 40 个 Unicode 码点，不追加省略号；按码点边界切分（emoji 不被劈开）。
    UUID sessionId = TestIds.id(103);
    String emoji = "\uD83D\uDE00".repeat(60); // 60 个 emoji，每个 2 个码元 = 1 码点
    runtime.acceptCommands(
        newSession(sessionId, TestIds.id(104), List.of(userMessage("a" + emoji + "尾"))),
        AcceptancePreflight.IDENTITY);
    Session stored = store.transaction(tx -> tx.findSession(sessionId).orElseThrow());
    assertEquals(40, stored.name().codePointCount(0, stored.name().length()));
    assertTrue(stored.name().startsWith("a"));
    assertFalse(stored.name().endsWith("…"));
  }

  @Test
  void sessionNameFromUserTextWithOverlongTextStillNamesFromFirstForty() {
    // 自动命名路径不设 256 上限：>256 码点文本同样规范化为单行并取前 40 码点（无省略号）。
    UUID sessionId = TestIds.id(105);
    String overLong = "长文 ".repeat(120); // 远超 256 码点
    runtime.acceptCommands(
        newSession(sessionId, TestIds.id(106), List.of(userMessage(overLong))),
        AcceptancePreflight.IDENTITY);
    Session stored = store.transaction(tx -> tx.findSession(sessionId).orElseThrow());
    assertEquals(40, stored.name().codePointCount(0, stored.name().length()));
    assertTrue(stored.name().startsWith("长文 长文"));
    assertFalse(stored.name().endsWith("…"));
  }

  @Test
  void sessionNameIsDerivedFromTrailingUserLikeMessageOnly() {
    // 初始 Session 名只取 validate 后末尾 user-like command：前缀 SYSTEM 文本不参与命名。
    UUID sessionId = TestIds.id(107);
    UUID threadId = TestIds.id(108);
    runtime.acceptCommands(
        newSession(
            sessionId,
            threadId,
            List.of(
                HarnessRuntimeTestSupport.systemCustomMessageCommand(
                    UUID.randomUUID(), "prefix system text"),
                userMessage("trailing user text"))),
        AcceptancePreflight.IDENTITY);
    Session stored = store.transaction(tx -> tx.findSession(sessionId).orElseThrow());
    assertEquals("trailing user text", stored.name());
  }

  @Test
  void sessionNameFallsBackToSessionUuidPrefixWhenNoText() {
    // 初始 user-like 消息无文本内容时回退 session- + UUID 前 8 位。
    UUID sessionId = TestIds.id(201);
    UUID threadId = TestIds.id(202);
    // 无文本的 user message：contents 只有一个空格文本
    NewThreadCommand blankText =
        HarnessRuntimeTestSupport.userMessageCommand(UUID.randomUUID(), " ");
    AcceptedCommands accepted =
        runtime.acceptCommands(
            newSession(sessionId, threadId, List.of(blankText)), AcceptancePreflight.IDENTITY);
    assertEquals("session-" + sessionId.toString().substring(0, 8), accepted.session().name());
    assertNotEquals("session-", accepted.session().name());
  }

  @Test
  void rootThreadIsAlwaysNamedMain() {
    // NEW_SESSION 创建的 ROOT Thread 固定命名为 main。
    UUID sessionId = TestIds.id(301);
    UUID threadId = TestIds.id(302);
    AcceptedCommands accepted =
        runtime.acceptCommands(
            newSession(sessionId, threadId, List.of(userMessage("hello"))),
            AcceptancePreflight.IDENTITY);
    assertEquals("main", accepted.thread().name());
    ThreadState stored = store.transaction(tx -> tx.findThread(threadId).orElseThrow());
    assertEquals("main", stored.name());
  }

  @Test
  void newThreadFromEntryIsNamedBranchWithThreadUuidPrefix() {
    // NEW_THREAD 创建的分支 Thread 固定命名为 branch- + Thread UUID 前 8 位；Session 名不被触碰。
    Baseline baseline = HarnessRuntimeTestSupport.seedBaseline(store);
    UUID branchThreadId = TestIds.id(401);
    AcceptCommandsCommand command =
        new AcceptCommandsCommand(
            new AcceptCommandsTarget.NewThread(
                baseline.sessionId(), baseline.rootEntryId(), branchThreadId, false),
            List.of(userMessage("branch hello")));
    AcceptedCommands accepted = runtime.acceptCommands(command, AcceptancePreflight.IDENTITY);
    assertEquals("branch-" + branchThreadId.toString().substring(0, 8), accepted.thread().name());
    // 分支创建不改既有 Session 名称（seedBaseline 直接以 session-<uuid> 落库）。
    Session session = store.transaction(tx -> tx.findSession(baseline.sessionId()).orElseThrow());
    assertEquals("session-" + baseline.sessionId(), session.name());
  }

  @Test
  void exactReplayAfterRenameReturnsCurrentNamesAndKeepsCreationHash() {
    // rename 后对同一初始创建请求做 exact replay：命中现有 Thread/Session 并返回当前名称，不写任何行，
    // creationRequestHash 不变（名称不参与创建指纹）。
    UUID sessionId = TestIds.id(501);
    UUID threadId = TestIds.id(502);
    NewThreadCommand first = userMessage("first text");
    runtime.acceptCommands(
        newSession(sessionId, threadId, List.of(first)), AcceptancePreflight.IDENTITY);
    Session renamedSession = runtime.renameSession(new RenameSessionCommand(sessionId, "会话"));
    ThreadState renamedThread = runtime.renameThread(new RenameThreadCommand(threadId, "主线"));

    String hashBefore =
        store.transaction(tx -> tx.findThread(threadId).orElseThrow()).creationRequestHash();
    AcceptedCommands replayed =
        runtime.acceptCommands(
            newSession(sessionId, threadId, List.of(first)), AcceptancePreflight.IDENTITY);
    assertTrue(replayed.replayed());
    assertEquals("会话", replayed.session().name());
    assertEquals("主线", replayed.thread().name());
    assertEquals(renamedSession, replayed.session());
    assertEquals(renamedThread, replayed.thread());
    assertEquals(hashBefore, replayed.thread().creationRequestHash());
  }
}
