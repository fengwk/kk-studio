package fun.fengwk.kkstudio.web.runtime;

import static fun.fengwk.kkstudio.web.runtime.HarnessRuntimeRequestMapperTestSupport.THREAD_ID;
import static fun.fengwk.kkstudio.web.runtime.HarnessRuntimeRequestMapperTestSupport.command;
import static fun.fengwk.kkstudio.web.runtime.HarnessRuntimeRequestMapperTestSupport.cursorBatch;
import static fun.fengwk.kkstudio.web.runtime.HarnessRuntimeRequestMapperTestSupport.executionSelection;
import static fun.fengwk.kkstudio.web.runtime.HarnessRuntimeRequestMapperTestSupport.idText;
import static fun.fengwk.kkstudio.web.runtime.HarnessRuntimeRequestMapperTestSupport.newSessionTarget;
import static fun.fengwk.kkstudio.web.runtime.HarnessRuntimeRequestMapperTestSupport.request;
import static fun.fengwk.kkstudio.web.runtime.HarnessRuntimeRequestMapperTestSupport.threadBatch;
import static fun.fengwk.kkstudio.web.runtime.HarnessRuntimeRequestMapperTestSupport.userCommand;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.api.AcceptCommandsCommand;
import fun.fengwk.kkstudio.harness.api.AcceptCommandsTarget;
import fun.fengwk.kkstudio.harness.api.command.ThreadCommandType;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessCommandCreateDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessThreadCommandBatchDTO;

import java.util.List;

class HarnessRuntimeRequestMapperBatchTest {

  @Test
  void requiresAtMostOneTrailingUserLikeAndOrderedSetPrefixOnExistingThreads() {
    // 既有 Thread 的 batch 使用单调 SET_* 前缀；user-like 至多一条且必须以它收尾。
    HarnessCommandCreateDTO agent = command("SET_AGENT", "agent");
    agent.setAgentName("default-assistant");
    assertThrows(
        IllegalArgumentException.class,
        () ->
            HarnessRuntimeRequestMapper.toAcceptThreadCommandsCommand(
                THREAD_ID, threadBatch(userCommand("user"), agent)));
    // 纯设置批次在既有 Thread 上合法：只允许 SET_*，不含任何 user-like 输入。
    AcceptCommandsCommand settingsOnly =
        HarnessRuntimeRequestMapper.toAcceptThreadCommandsCommand(THREAD_ID, threadBatch(agent));
    assertEquals(
        List.of(ThreadCommandType.SET_AGENT),
        settingsOnly.commands().stream().map(command -> command.payload().type()).toList());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            HarnessRuntimeRequestMapper.toAcceptThreadCommandsCommand(
                THREAD_ID, threadBatch(userCommand("one"), userCommand("two"))));

    HarnessCommandCreateDTO duplicateAgent = command("SET_AGENT", "duplicate-agent");
    duplicateAgent.setAgentName("other-assistant");
    assertThrows(
        IllegalArgumentException.class,
        () ->
            HarnessRuntimeRequestMapper.toAcceptThreadCommandsCommand(
                THREAD_ID, threadBatch(agent, duplicateAgent, userCommand("user"))));

    HarnessCommandCreateDTO execution = command("SET_EXECUTION", "execution");
    execution.setExecution(executionSelection());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            HarnessRuntimeRequestMapper.toAcceptThreadCommandsCommand(
                THREAD_ID, threadBatch(execution, agent, userCommand("user"))));

    HarnessThreadCommandBatchDTO empty = threadBatch(userCommand("unused"));
    empty.setCommands(List.of());
    assertThrows(
        IllegalArgumentException.class,
        () -> HarnessRuntimeRequestMapper.toAcceptThreadCommandsCommand(THREAD_ID, empty));
  }

  /**
   * 测试意图：纯设置批次（0 条 user-like）只在既有 Thread 上被接受，且保持 SET_AGENT -> SET_EXECUTION -> SET_ENVIRONMENT
   * 固定顺序；创建型 target 与本地草稿预览仍要求恰一条末尾 user-like 输入。
   */
  @Test
  void acceptsSettingsOnlyBatchOnExistingThreadButNotOnCreationTargets() {
    HarnessCommandCreateDTO agent = command("SET_AGENT", "settings-agent");
    agent.setAgentName("coder");
    HarnessCommandCreateDTO execution = command("SET_EXECUTION", "settings-execution");
    execution.setExecution(executionSelection());
    HarnessCommandCreateDTO environment = command("SET_ENVIRONMENT", "settings-environment");
    environment.setEnvironmentName(null);

    AcceptCommandsCommand mapped =
        HarnessRuntimeRequestMapper.toAcceptThreadCommandsCommand(
            THREAD_ID, threadBatch(agent, execution, environment));
    assertEquals(
        List.of(
            ThreadCommandType.SET_AGENT,
            ThreadCommandType.SET_EXECUTION,
            ThreadCommandType.SET_ENVIRONMENT),
        mapped.commands().stream().map(command -> command.payload().type()).toList());

    // 创建型 target 不接受纯设置批次：必须恰有一条末尾 user-like 输入。
    assertThrows(
        IllegalArgumentException.class,
        () ->
            HarnessRuntimeRequestMapper.toAcceptCommandsCommand(
                request(newSessionTarget(), agent, execution)));
    // 本地分支草稿预览沿用创建契约，同样拒绝纯设置批次。
    assertThrows(
        IllegalArgumentException.class,
        () ->
            HarnessRuntimeRequestMapper.toNewThreadCommands(List.of(agent, execution), "commands"));
  }

  /** 测试意图：既有 Thread 批次的 CAS cursor 必须精确映射到 domain target，命令列表缺失/为空一律拒绝。 */
  @Test
  void mapsThreadBatchCursorAndRequiresCommands() {
    HarnessCommandCreateDTO agent = command("SET_AGENT", "agent");
    agent.setAgentName("default-assistant");
    AcceptCommandsTarget.Thread target =
        (AcceptCommandsTarget.Thread)
            HarnessRuntimeRequestMapper.toAcceptThreadCommandsCommand(
                    THREAD_ID, threadBatch(agent, userCommand("user")))
                .target();
    assertEquals(idText(3), target.expectedHeadEntryId().toString());
    assertEquals(4L, target.expectedNextCommandSequence());

    HarnessThreadCommandBatchDTO empty = new HarnessThreadCommandBatchDTO();
    empty.setExpectedHeadEntryId(idText(3));
    empty.setExpectedNextCommandSequence("4");
    assertThrows(
        IllegalArgumentException.class,
        () -> HarnessRuntimeRequestMapper.toAcceptThreadCommandsCommand(THREAD_ID, empty));
  }

  @Test
  void pureUserMessageBatchOmitsCursorAndOtherBatchesRequireIt() {
    // 单条纯 USER_MESSAGE 不带 cursor；SET 前缀混合批次与设置批次缺 cursor 一律 400。
    HarnessThreadCommandBatchDTO cursorless = new HarnessThreadCommandBatchDTO();
    cursorless.setCommands(List.of(userCommand("cursorless")));
    AcceptCommandsCommand mapped =
        HarnessRuntimeRequestMapper.toAcceptThreadCommandsCommand(THREAD_ID, cursorless);
    AcceptCommandsTarget.Thread target =
        assertInstanceOf(AcceptCommandsTarget.Thread.class, mapped.target());
    assertFalse(target.hasCursor());
    assertNull(target.expectedHeadEntryId());
    assertNull(target.expectedNextCommandSequence());

    HarnessCommandCreateDTO agent = command("SET_AGENT", "agent");
    agent.setAgentName("default-assistant");
    HarnessThreadCommandBatchDTO mixedWithoutCursor = new HarnessThreadCommandBatchDTO();
    mixedWithoutCursor.setCommands(List.of(agent, userCommand("mixed")));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            HarnessRuntimeRequestMapper.toAcceptThreadCommandsCommand(
                THREAD_ID, mixedWithoutCursor));
    HarnessThreadCommandBatchDTO settingsWithoutCursor = new HarnessThreadCommandBatchDTO();
    settingsWithoutCursor.setCommands(List.of(agent));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            HarnessRuntimeRequestMapper.toAcceptThreadCommandsCommand(
                THREAD_ID, settingsWithoutCursor));

    // 纯消息携带任一 cursor 字段都是输入错误。
    assertThrows(
        IllegalArgumentException.class,
        () ->
            HarnessRuntimeRequestMapper.toAcceptThreadCommandsCommand(
                THREAD_ID, cursorBatch(userCommand("cursor"))));
    HarnessThreadCommandBatchDTO partialCursor = new HarnessThreadCommandBatchDTO();
    partialCursor.setExpectedHeadEntryId(idText(3));
    partialCursor.setCommands(List.of(userCommand("partial")));
    assertThrows(
        IllegalArgumentException.class,
        () -> HarnessRuntimeRequestMapper.toAcceptThreadCommandsCommand(THREAD_ID, partialCursor));
    HarnessThreadCommandBatchDTO malformedCursor = new HarnessThreadCommandBatchDTO();
    malformedCursor.setExpectedHeadEntryId("not-a-uuid");
    malformedCursor.setExpectedNextCommandSequence("4");
    malformedCursor.setCommands(List.of(userCommand("malformed")));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            HarnessRuntimeRequestMapper.toAcceptThreadCommandsCommand(THREAD_ID, malformedCursor));

    // 预览依赖当前视图，即使纯消息也必须提供精确 cursor。
    assertThrows(
        IllegalArgumentException.class,
        () -> HarnessRuntimeRequestMapper.toPreviewThreadCommandsCommand(THREAD_ID, cursorless));
    assertTrue(
        ((AcceptCommandsTarget.Thread)
                HarnessRuntimeRequestMapper.toPreviewThreadCommandsCommand(
                        THREAD_ID, cursorBatch(userCommand("preview")))
                    .target())
            .hasCursor());
  }
}
