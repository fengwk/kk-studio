package fun.fengwk.kkstudio.core.harness.tool.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import fun.fengwk.kkstudio.core.harness.task.store.mapper.HarnessSubagentTaskMapper;
import fun.fengwk.kkstudio.core.harness.task.store.model.HarnessSubagentTaskDO;
import fun.fengwk.kkstudio.core.harness.thread.store.mapper.HarnessThreadEventMapper;
import fun.fengwk.kkstudio.core.harness.thread.store.mapper.HarnessThreadMapper;
import fun.fengwk.kkstudio.core.harness.thread.store.model.HarnessThreadDO;
import fun.fengwk.kkstudio.core.harness.thread.store.model.HarnessThreadEventDO;
import fun.fengwk.kkstudio.core.harness.tool.store.mapper.ToolInvocationMapper;
import fun.fengwk.kkstudio.harness.model.ModelCost;
import fun.fengwk.kkstudio.harness.model.ModelPricing;
import fun.fengwk.kkstudio.harness.model.ModelUsage;
import fun.fengwk.kkstudio.harness.model.cache.PromptCacheMode;
import fun.fengwk.kkstudio.harness.model.cache.PromptCacheRetention;
import fun.fengwk.kkstudio.harness.model.provider.ProviderStopReason;
import fun.fengwk.kkstudio.harness.model.provider.ProviderType;
import fun.fengwk.kkstudio.harness.runtime.permission.PermissionAction;
import fun.fengwk.kkstudio.harness.runtime.permission.PermissionPromptPreview;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.session.AssistantMessageMetadata;
import fun.fengwk.kkstudio.harness.runtime.session.MessageEntryPayload;
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.ToolCallMessageContent;
import fun.fengwk.kkstudio.harness.runtime.task.TaskState;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadEventType;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadStatus;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadTransactions;
import fun.fengwk.kkstudio.harness.runtime.tool.PreparedToolInvocation;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolBinding;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolInvocationStatus;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolPreparationService;
import fun.fengwk.kkstudio.harness.runtime.usage.ModelUsageDraft;
import fun.fengwk.kkstudio.harness.runtime.usage.ModelUsageRecordStore;
import fun.fengwk.kkstudio.harness.tool.ToolCall;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolExecutionMode;
import fun.fengwk.kkstudio.harness.tool.ToolSideEffect;
import fun.fengwk.kkstudio.harness.tool.schema.ToolParamsSchema;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * ASK preparation durably writes the child event and a root-only relay carrying the same invocation
 * id.
 */
@SpringBootTest
class HarnessThreadPermissionRelayIntegrationTest {
  private static final long ROOT_THREAD_ID = 9_720_001L;
  private static final long CHILD_THREAD_ID = 9_720_002L;
  private static final long CHILD_SESSION_ID = 9_720_101L;
  private static final long ASSISTANT_ENTRY_ID = 9_720_201L;
  private static final long INVOCATION_ID = 9_720_301L;
  private static final String PROCESSOR_TOKEN = "permission-relay";

  @MockitoBean private ToolPreparationService toolPreparationService;
  @MockitoBean private ModelUsageRecordStore usageRecordStore;

  @Autowired private ThreadTransactions transactions;
  @Autowired private HarnessThreadMapper threadMapper;
  @Autowired private HarnessThreadEventMapper eventMapper;
  @Autowired private HarnessSubagentTaskMapper taskMapper;
  @Autowired private ToolInvocationMapper invocationMapper;
  @Autowired private JdbcTemplate jdbc;

  @BeforeEach
  void clean() {
    jdbc.update("delete from harness_thread_event");
    jdbc.update("delete from harness_subagent_task");
    jdbc.update("delete from tool_invocation");
    jdbc.update("delete from harness_thread_input");
    jdbc.update("delete from harness_thread");
    jdbc.update("delete from harness_session_entry");
  }

  @Test
  void askedChildInvocationWritesSourceAndRootRequestedEvents() {
    LocalDateTime timestamp = LocalDateTime.now(ZoneOffset.UTC);
    Instant now = timestamp.toInstant(ZoneOffset.UTC);
    insertThread(ROOT_THREAD_ID, ROOT_THREAD_ID + 10, null, timestamp);
    insertThread(CHILD_THREAD_ID, CHILD_SESSION_ID, PROCESSOR_TOKEN, timestamp);
    insertTask(timestamp);
    ToolBinding binding = binding();
    ToolCall call = new ToolCall("call-ask", "write", "{}");
    PreparedToolInvocation asked =
        new PreparedToolInvocation(
            INVOCATION_ID,
            0,
            binding,
            call,
            PermissionAction.ASK,
            ToolInvocationStatus.WAITING_APPROVAL,
            now.plusSeconds(30),
            new PermissionPromptPreview("write", "/work", "{}"),
            null,
            null);
    when(toolPreparationService.prepare(any(), any(), any(), anyBoolean(), any(), any(), any()))
        .thenReturn(List.of(asked));
    ModelUsageDraft usageDraft = usageDraft();

    boolean prepared =
        transactions.prepareTools(
            CHILD_THREAD_ID,
            PROCESSOR_TOKEN,
            ASSISTANT_ENTRY_ID,
            new MessageEntryPayload(
                new AgentMessage(
                    AgentMessageRole.ASSISTANT,
                    List.of(
                        new TextMessageContent("calling tool"),
                        new ToolCallMessageContent("call-ask", "write", "{}"))),
                new AssistantMessageMetadata(
                    ProviderStopReason.TOOL_CALLS, usageDraft.usage(), usageDraft.cost())),
            usageDraft,
            List.of(call),
            List.of(binding),
            null,
            null,
            false,
            List.of(),
            now);

    assertTrue(prepared);
    assertEquals(
        ToolInvocationStatus.WAITING_APPROVAL.name(),
        invocationMapper.find(INVOCATION_ID).getStatus());
    List<HarnessThreadEventDO> childEvents = eventMapper.listAfter(CHILD_THREAD_ID, 0L, 10);
    List<HarnessThreadEventDO> rootEvents = eventMapper.listAfter(ROOT_THREAD_ID, 0L, 10);
    HarnessThreadEventDO childRequested = permissionRequested(childEvents);
    HarnessThreadEventDO rootRequested = permissionRequested(rootEvents);
    assertEquals(ASSISTANT_ENTRY_ID, childRequested.getSubjectEntryId());
    assertEquals(null, rootRequested.getSubjectEntryId());
    assertEquals(childRequested.getPayloadJson(), rootRequested.getPayloadJson());
    assertTrue(rootRequested.getPayloadJson().contains("\"invocationId\":\"" + INVOCATION_ID));
  }

  private void insertThread(
      long id, long sessionId, String processorToken, LocalDateTime timestamp) {
    HarnessThreadDO thread = new HarnessThreadDO();
    thread.setId(id);
    thread.setSessionId(sessionId);
    thread.setHeadEntryId(id + 100);
    thread.setStatus(ThreadStatus.RUNNING.name());
    thread.setProcessorToken(processorToken);
    thread.setProcessorUntil(timestamp.plusMinutes(5));
    thread.setInputSequence(0L);
    thread.setVersion(0L);
    thread.setCreateTime(timestamp);
    thread.setUpdateTime(timestamp);
    threadMapper.insert(thread);
  }

  private void insertTask(LocalDateTime timestamp) {
    HarnessSubagentTaskDO task = new HarnessSubagentTaskDO();
    task.setParentInvocationId(INVOCATION_ID + 1);
    task.setParentSessionId(ROOT_THREAD_ID + 20);
    task.setParentThreadId(ROOT_THREAD_ID);
    task.setRootThreadId(ROOT_THREAD_ID);
    task.setChildSessionId(CHILD_SESSION_ID);
    task.setChildThreadId(CHILD_THREAD_ID);
    task.setTargetAgent("child");
    task.setWorkingCopyPolicy("NONE");
    task.setMaxTurns(3);
    task.setStatus(TaskState.RUNNING.name());
    task.setCreateTime(timestamp);
    task.setUpdateTime(timestamp);
    taskMapper.insert(task);
  }

  private static HarnessThreadEventDO permissionRequested(List<HarnessThreadEventDO> events) {
    return events.stream()
        .filter(event -> ThreadEventType.PERMISSION_REQUESTED.value().equals(event.getEventType()))
        .findFirst()
        .orElseThrow();
  }

  private static ToolBinding binding() {
    ToolDescriptor descriptor =
        new ToolDescriptor(
            "write",
            "1",
            "write",
            null,
            new ToolParamsSchema(null, Map.of(), Set.of(), false),
            ToolExecutionMode.CLOUD,
            ToolSideEffect.NON_IDEMPOTENT,
            Duration.ofSeconds(30));
    return ToolBinding.of(descriptor);
  }

  private static ModelUsageDraft usageDraft() {
    ModelUsage usage = new ModelUsage(1, 0, 0, 0, 0, 0, 1);
    ModelPricing pricing =
        new ModelPricing(
            "USD",
            "tier",
            "default",
            BigDecimal.ONE.setScale(12),
            "v1",
            BigDecimal.ZERO.setScale(12),
            BigDecimal.ZERO.setScale(12),
            BigDecimal.ZERO.setScale(12),
            BigDecimal.ZERO.setScale(12),
            BigDecimal.ZERO.setScale(12),
            BigDecimal.ZERO.setScale(12));
    return new ModelUsageDraft(
        1L,
        2L,
        ProviderType.OPENAI,
        "model",
        PromptCacheMode.UNSUPPORTED,
        PromptCacheRetention.NONE,
        false,
        null,
        ProviderStopReason.COMPLETED,
        usage,
        ModelCost.calculate(pricing, usage),
        pricing,
        null,
        null,
        "{}");
  }
}
