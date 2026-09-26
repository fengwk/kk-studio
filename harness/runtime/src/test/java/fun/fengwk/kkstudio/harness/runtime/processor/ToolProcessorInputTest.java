package fun.fengwk.kkstudio.harness.runtime.processor;

import static fun.fengwk.kkstudio.harness.runtime.processor.ToolProcessorTestSupport.NOW;
import static fun.fengwk.kkstudio.harness.runtime.processor.ToolProcessorTestSupport.NO_RETRY;
import static fun.fengwk.kkstudio.harness.runtime.processor.ToolProcessorTestSupport.askUserFixture;
import static fun.fengwk.kkstudio.harness.runtime.processor.ToolProcessorTestSupport.askUserScenario;
import static fun.fengwk.kkstudio.harness.runtime.processor.ToolProcessorTestSupport.claim;
import static fun.fengwk.kkstudio.harness.runtime.processor.ToolProcessorTestSupport.thread;
import static fun.fengwk.kkstudio.harness.runtime.processor.ToolProcessorTestSupport.threadWork;
import static fun.fengwk.kkstudio.harness.runtime.processor.ToolProcessorTestSupport.tool;
import static fun.fengwk.kkstudio.harness.runtime.processor.ToolProcessorTestSupport.toolWork;
import static fun.fengwk.kkstudio.harness.runtime.processor.ToolProcessorTestSupport.transition;
import static fun.fengwk.kkstudio.harness.runtime.processor.ToolProcessorTestSupport.untrustedAskUserScenario;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocation;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocationStatus;
import fun.fengwk.kkstudio.harness.runtime.processor.ToolProcessorTestSupport.Fixture;

/**
 * {@code ask_user} 的 READY 边界：只有可信 provenance 的内置人输入工具才被冻结为 WAITING_INPUT。
 *
 * <p>覆盖四件事：等待是接受而非执行（不 dispatch、不消耗 attempt、不 request THREAD，YOLO 也不作答）；问卷是接受时的硬契约 （不可解析即确定性失败并唤醒
 * Thread，且不回显问卷原文）；同名但非内置贡献的调用必须当普通工具处理，绝不因为模型给出的名字而被误判为等待； 已处于等待的重复/迟到 claim 只 complete TOOL Work。
 */
class ToolProcessorInputTest {

  private static final String QUESTIONNAIRE =
      "{\"questions\":[{\"question\":\"which plan?\",\"options\":[{\"label\":\"fast\"},{\"label\":\"safe\"}]}]}";

  /** 内置 ask_user：READY -&gt; WAITING_INPUT，version+1，complete TOOL Work，不执行、不唤醒 Thread。 */
  @Test
  void builtinAskUserIsParkedAsWaitingInputWithoutExecuting() {
    Fixture fixture = askUserFixture(QUESTIONNAIRE);

    assertEquals(
        ProcessResult.TERMINATED,
        fixture.processor.process(claim(fixture.store, fixture.toolInvocationId, NOW)));

    ToolInvocation waiting = tool(fixture.store, fixture.toolInvocationId);
    assertEquals(ToolInvocationStatus.WAITING_INPUT, waiting.status());
    assertEquals(0, waiting.attempt());
    assertNull(waiting.approval());
    assertNull(waiting.result());
    assertNull(waiting.error());
    assertEquals(QUESTIONNAIRE, waiting.call().argumentsJson());
    // baseline thread version 为 0：接受等待恰好推进一次。
    assertEquals(1L, thread(fixture.store, fixture.baseline.threadId()).version());
    assertNull(toolWork(fixture.store, fixture.toolInvocationId));
    // 冻结等待不唤醒 Thread：fixture 预置的 THREAD Work 保持原 wakeVersion，不被接受路径重新请求。
    assertEquals(1L, threadWork(fixture.store, fixture.baseline.threadId()).wakeVersion());
    // 等待是接受而非执行：既不 preflight 也不 start，且不持有本地 execution。
    assertEquals(0, fixture.gateway.preflightCallsCount);
    assertEquals(0, fixture.gateway.startCalls);
    assertFalse(fixture.processor.hasActiveExecution());
  }

  /** YOLO 不代替用户作答：即使 branch 打开 YOLO，ask_user 仍然冻结为等待。 */
  @Test
  void yoloDoesNotAnswerTheQuestionnaire() {
    Fixture fixture =
        new Fixture(
            NO_RETRY,
            true,
            ToolProcessorTestSupport.newScheduler(),
            askUserScenario(QUESTIONNAIRE));

    assertEquals(
        ProcessResult.TERMINATED,
        fixture.processor.process(claim(fixture.store, fixture.toolInvocationId, NOW)));

    assertEquals(
        ToolInvocationStatus.WAITING_INPUT, tool(fixture.store, fixture.toolInvocationId).status());
    assertEquals(1L, thread(fixture.store, fixture.baseline.threadId()).version());
    assertEquals(0, fixture.gateway.preflightCallsCount);
    assertEquals(0, fixture.gateway.startCalls);
  }

  /** 非内置来源的同名调用：必须走普通工具路径（preflight + 执行），不得被名字误导为等待。 */
  @Test
  void nonBuiltinToolNamedAskUserIsExecutedAsAnOrdinaryTool() {
    Fixture fixture =
        new Fixture(
            NO_RETRY, false, ToolProcessorTestSupport.newScheduler(), untrustedAskUserScenario());
    fixture.gateway.queuePreflightAllow();

    fixture.processor.process(claim(fixture.store, fixture.toolInvocationId, NOW));

    assertTrue(fixture.gateway.preflightCallsCount >= 1);
    ToolInvocation processed = tool(fixture.store, fixture.toolInvocationId);
    assertFalse(processed.status() == ToolInvocationStatus.WAITING_INPUT);
  }

  /** 问卷不可解析：确定性失败 + 唤醒 Thread，绝不冻结一个无法作答的等待。 */
  @Test
  void unparseableQuestionnaireFailsDeterministicallyAndWakesThread() {
    Fixture fixture = askUserFixture("{\"questions\":[{\"question\":\"q\",\"unexpected\":true}]}");

    assertEquals(
        ProcessResult.TERMINATED,
        fixture.processor.process(claim(fixture.store, fixture.toolInvocationId, NOW)));

    ToolInvocation failed = tool(fixture.store, fixture.toolInvocationId);
    assertEquals(ToolInvocationStatus.FAILED, failed.status());
    assertEquals("INVALID_QUESTIONNAIRE", failed.error().kind());
    assertEquals(0, failed.attempt());
    // 失败信息只报告契约原因，不回显问卷原文。
    assertFalse(failed.error().message().contains("unexpected"));
    assertEquals(1L, thread(fixture.store, fixture.baseline.threadId()).version());
    assertNotNull(threadWork(fixture.store, fixture.baseline.threadId()));
    assertNull(toolWork(fixture.store, fixture.toolInvocationId));
    assertEquals(0, fixture.gateway.preflightCallsCount);
  }

  /** 已是 WAITING_INPUT 的 claim（重复 / 迟到投递）：只 complete TOOL Work，不 bump version、不打扰用户等待。 */
  @Test
  void waitingInputClaimOnlyCompletesToolWork() {
    Fixture fixture = askUserFixture(QUESTIONNAIRE);
    transition(fixture.store, fixture.toolInvocationId, t -> t.requestInput(NOW));

    assertEquals(
        ProcessResult.TERMINATED,
        fixture.processor.process(claim(fixture.store, fixture.toolInvocationId, NOW)));

    assertEquals(
        ToolInvocationStatus.WAITING_INPUT, tool(fixture.store, fixture.toolInvocationId).status());
    // 重复投递不推进 version，也不重新请求 THREAD Work。
    assertEquals(0L, thread(fixture.store, fixture.baseline.threadId()).version());
    assertEquals(1L, threadWork(fixture.store, fixture.baseline.threadId()).wakeVersion());
    assertNull(toolWork(fixture.store, fixture.toolInvocationId));
    assertEquals(0, fixture.gateway.preflightCallsCount);
    assertEquals(0, fixture.gateway.startCalls);
  }
}
