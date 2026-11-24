package fun.fengwk.kkstudio.platform.harness.http;

import static fun.fengwk.kkstudio.platform.harness.http.HarnessRuntimeRequestMapperTestSupport.THREAD_ID;
import static fun.fengwk.kkstudio.platform.harness.http.HarnessRuntimeRequestMapperTestSupport.cursorBatch;
import static fun.fengwk.kkstudio.platform.harness.http.HarnessRuntimeRequestMapperTestSupport.executionSelection;
import static fun.fengwk.kkstudio.platform.harness.http.HarnessRuntimeRequestMapperTestSupport.idText;
import static fun.fengwk.kkstudio.platform.harness.http.HarnessRuntimeRequestMapperTestSupport.newSessionTarget;
import static fun.fengwk.kkstudio.platform.harness.http.HarnessRuntimeRequestMapperTestSupport.request;
import static fun.fengwk.kkstudio.platform.harness.http.HarnessRuntimeRequestMapperTestSupport.setEnvironment;
import static fun.fengwk.kkstudio.platform.harness.http.HarnessRuntimeRequestMapperTestSupport.setExecution;
import static fun.fengwk.kkstudio.platform.harness.http.HarnessRuntimeRequestMapperTestSupport.threadBatch;
import static fun.fengwk.kkstudio.platform.harness.http.HarnessRuntimeRequestMapperTestSupport.userCommand;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.common.error.DomainException;
import fun.fengwk.kkstudio.harness.api.AcceptCommandsCommand;
import fun.fengwk.kkstudio.harness.api.AcceptCommandsTarget;
import fun.fengwk.kkstudio.harness.api.command.ThreadCommandType;
import fun.fengwk.kkstudio.platform.harness.dto.HarnessCommandCreateDTO;
import fun.fengwk.kkstudio.platform.harness.dto.HarnessThreadCommandBatchDTO;

import java.util.List;

class HarnessRuntimeRequestMapperBatchTest {

  @Test
  void requiresAtMostOneTrailingUserLikeAndOrderedSetPrefixOnExistingThreads() {
    // 既有 Thread 的 batch 使用单调 SET_* 前缀；user-like 至多一条且必须以它收尾。
    HarnessCommandCreateDTO execution = setExecution("execution", executionSelection());
    assertThrows(
        DomainException.class,
        () ->
            HarnessRuntimeRequestMapper.toAcceptThreadCommandsCommand(
                THREAD_ID, threadBatch(userCommand("user"), execution)));
    // 纯设置批次在既有 Thread 上合法：只允许 SET_*，不含任何 user-like 输入。
    AcceptCommandsCommand settingsOnly =
        HarnessRuntimeRequestMapper.toAcceptThreadCommandsCommand(
            THREAD_ID, threadBatch(execution));
    assertEquals(
        List.of(ThreadCommandType.SET_EXECUTION),
        settingsOnly.commands().stream().map(command -> command.payload().type()).toList());
    assertThrows(
        DomainException.class,
        () ->
            HarnessRuntimeRequestMapper.toAcceptThreadCommandsCommand(
                THREAD_ID, threadBatch(userCommand("one"), userCommand("two"))));

    HarnessCommandCreateDTO duplicateExecution =
        setExecution("duplicate-execution", executionSelection());
    assertThrows(
        DomainException.class,
        () ->
            HarnessRuntimeRequestMapper.toAcceptThreadCommandsCommand(
                THREAD_ID, threadBatch(execution, duplicateExecution, userCommand("user"))));

    assertThrows(
        DomainException.class,
        () ->
            HarnessRuntimeRequestMapper.toAcceptThreadCommandsCommand(
                THREAD_ID, threadBatch(execution, userCommand("user"), userCommand("tail"))));

    HarnessThreadCommandBatchDTO empty = threadBatch(userCommand("unused"));
    empty.setCommands(List.of());
    assertThrows(
        DomainException.class,
        () -> HarnessRuntimeRequestMapper.toAcceptThreadCommandsCommand(THREAD_ID, empty));
  }

  /** 纯设置批次（0 条 user-like）只在既有 Thread 上被接受，且保持固定 SET_* 顺序；创建型 target 仍要求末尾 user-like 输入。 */
  @Test
  void acceptsSettingsOnlyBatchOnExistingThreadButNotOnCreationTargets() {
    HarnessCommandCreateDTO execution = setExecution("settings-execution", executionSelection());
    HarnessCommandCreateDTO environment = setEnvironment("settings-environment", null);

    AcceptCommandsCommand mapped =
        HarnessRuntimeRequestMapper.toAcceptThreadCommandsCommand(
            THREAD_ID, threadBatch(execution, environment));
    assertEquals(
        List.of(ThreadCommandType.SET_EXECUTION, ThreadCommandType.SET_ENVIRONMENT),
        mapped.commands().stream().map(command -> command.payload().type()).toList());

    assertThrows(
        DomainException.class,
        () ->
            HarnessRuntimeRequestMapper.toAcceptCommandsCommand(
                request(newSessionTarget(), execution, environment)));
  }

  /** 既有 Thread 批次的 CAS cursor 必须精确映射到 domain target，命令列表缺失/为空一律拒绝。 */
  @Test
  void mapsThreadBatchCursorAndRequiresCommands() {
    HarnessCommandCreateDTO execution = setExecution("execution", executionSelection());
    AcceptCommandsTarget.Thread target =
        (AcceptCommandsTarget.Thread)
            HarnessRuntimeRequestMapper.toAcceptThreadCommandsCommand(
                    THREAD_ID, threadBatch(execution, userCommand("user")))
                .target();
    assertEquals(idText(3), target.expectedHeadEntryId().toString());
    assertEquals(4L, target.expectedNextCommandSequence());

    HarnessThreadCommandBatchDTO empty = new HarnessThreadCommandBatchDTO();
    empty.setExpectedHeadEntryId(idText(3));
    empty.setExpectedNextCommandSequence(4L);
    assertThrows(
        DomainException.class,
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

    HarnessCommandCreateDTO execution = setExecution("execution", executionSelection());
    HarnessThreadCommandBatchDTO mixedWithoutCursor = new HarnessThreadCommandBatchDTO();
    mixedWithoutCursor.setCommands(List.of(execution, userCommand("mixed")));
    assertThrows(
        DomainException.class,
        () ->
            HarnessRuntimeRequestMapper.toAcceptThreadCommandsCommand(
                THREAD_ID, mixedWithoutCursor));
    HarnessThreadCommandBatchDTO settingsWithoutCursor = new HarnessThreadCommandBatchDTO();
    settingsWithoutCursor.setCommands(List.of(execution));
    assertThrows(
        DomainException.class,
        () ->
            HarnessRuntimeRequestMapper.toAcceptThreadCommandsCommand(
                THREAD_ID, settingsWithoutCursor));

    // 纯消息携带任一 cursor 字段都是输入错误。
    assertThrows(
        DomainException.class,
        () ->
            HarnessRuntimeRequestMapper.toAcceptThreadCommandsCommand(
                THREAD_ID, cursorBatch(userCommand("cursor"))));
    HarnessThreadCommandBatchDTO partialCursor = new HarnessThreadCommandBatchDTO();
    partialCursor.setExpectedHeadEntryId(idText(3));
    partialCursor.setCommands(List.of(userCommand("partial")));
    assertThrows(
        DomainException.class,
        () -> HarnessRuntimeRequestMapper.toAcceptThreadCommandsCommand(THREAD_ID, partialCursor));
    HarnessThreadCommandBatchDTO malformedCursor = new HarnessThreadCommandBatchDTO();
    malformedCursor.setExpectedHeadEntryId("not-a-uuid");
    malformedCursor.setExpectedNextCommandSequence(4L);
    malformedCursor.setCommands(List.of(userCommand("malformed")));
    assertThrows(
        DomainException.class,
        () ->
            HarnessRuntimeRequestMapper.toAcceptThreadCommandsCommand(THREAD_ID, malformedCursor));
  }
}
