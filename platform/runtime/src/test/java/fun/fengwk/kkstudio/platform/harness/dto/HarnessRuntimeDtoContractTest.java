package fun.fengwk.kkstudio.platform.harness.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonTypeName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import fun.fengwk.kkstudio.harness.api.history.EntryType;
import fun.fengwk.kkstudio.harness.api.thread.ThreadExecutionControl;
import fun.fengwk.kkstudio.harness.api.thread.ThreadRuntimeStatus;

import java.time.Instant;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/** Harness HTTP DTO 契约：sealed union、固定枚举、请求严格性与明确 nullable 的 wire 形态。 */
class HarnessRuntimeDtoContractTest {

  private static final JsonMapper MAPPER = JsonMapper.builder().build();

  private static final JsonMapper STRICT_MAPPER =
      JsonMapper.builder().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();

  private static final JsonMapper NON_NULL_MAPPER =
      JsonMapper.builder()
          .changeDefaultPropertyInclusion(
              inclusion -> inclusion.withValueInclusion(JsonInclude.Include.NON_NULL))
          .build();

  @Test
  void commandUnionRoundTripsTypedVariantsAndExplicitNulls() {
    HarnessCommandBatchDTO batch =
        MAPPER.readValue(
            """
            {
              "owner": {"kind": "CHAT", "id": "chat-1"},
              "target": {"type": "NEW_SESSION", "sessionId": "s-1", "threadId": "t-1", "yoloEnabled": false},
              "commands": [
                {"type": "GOAL", "idempotencyKey": "k-1", "text": null},
                {"type": "SET_ENVIRONMENT", "idempotencyKey": "k-2", "environmentName": null},
                {"type": "USER_MESSAGE", "idempotencyKey": "k-3", "contents": [
                  {"type": "TEXT", "text": "hi"},
                  {"type": "ATTACHMENT", "uploadId": "u-1", "imageTier": "1080P"}
                ]}
              ]
            }
            """,
            HarnessCommandBatchDTO.class);

    assertInstanceOf(HarnessCommandTargetDTO.NewSessionDTO.class, batch.getTarget());
    assertEquals(3, batch.getCommands().size());

    HarnessCommandCreateDTO.GoalDTO goal =
        assertInstanceOf(HarnessCommandCreateDTO.GoalDTO.class, batch.getCommands().get(0));
    assertNull(goal.text());
    HarnessCommandCreateDTO.SetEnvironmentDTO environment =
        assertInstanceOf(
            HarnessCommandCreateDTO.SetEnvironmentDTO.class, batch.getCommands().get(1));
    assertNull(environment.environmentName());

    HarnessCommandCreateDTO.UserMessageDTO message =
        assertInstanceOf(HarnessCommandCreateDTO.UserMessageDTO.class, batch.getCommands().get(2));
    HarnessUserMessageContentDTO.TextDTO text =
        assertInstanceOf(HarnessUserMessageContentDTO.TextDTO.class, message.contents().get(0));
    assertEquals("hi", text.text());
    HarnessUserMessageContentDTO.AttachmentDTO attachment =
        assertInstanceOf(
            HarnessUserMessageContentDTO.AttachmentDTO.class, message.contents().get(1));
    assertEquals(HarnessImageInputTier.P1080, attachment.imageTier());
  }

  @Test
  void sealedUnionsExposeOnlyFinalVariants() {
    assertTrue(HarnessCommandCreateDTO.class.isSealed());
    assertTrue(HarnessCommandTargetDTO.class.isSealed());
    assertTrue(HarnessUserMessageContentDTO.class.isSealed());
    assertEquals(
        Set.of("USER_MESSAGE", "GOAL", "SET_AGENT", "SET_EXECUTION", "SET_ENVIRONMENT"),
        subtypes(HarnessCommandCreateDTO.class));
    assertEquals(
        Set.of("NEW_SESSION", "NEW_THREAD", "NEW_FORKED_SESSION"),
        subtypes(HarnessCommandTargetDTO.class));
    assertEquals(
        Set.of("TEXT", "ATTACHMENT", "RESOURCE"), subtypes(HarnessUserMessageContentDTO.class));
  }

  @Test
  void commandUnionRejectsUnknownDiscriminatorAndUnknownField() {
    assertThrows(
        Exception.class,
        () ->
            MAPPER.readValue(
                """
                {"type": "SET_UNKNOWN", "idempotencyKey": "k"}
                """,
                HarnessCommandCreateDTO.class));
    assertThrows(
        Exception.class,
        () ->
            STRICT_MAPPER.readValue(
                """
                {"type": "TEXT", "text": "hi", "legacy": true}
                """,
                HarnessUserMessageContentDTO.class));
  }

  @Test
  void entryProjectionUsesFinalEnumAndAtFieldNames() throws Exception {
    HarnessSessionEntryDTO entry =
        MAPPER.readValue(
            """
            {
              "entryId": "e-1",
              "sessionId": "s-1",
              "parentEntryId": null,
              "entryType": "MESSAGE",
              "payloadJson": "{}",
              "usageCost": null
            }
            """,
            HarnessSessionEntryDTO.class);

    assertEquals(EntryType.MESSAGE, entry.getEntryType());
    assertEquals(
        Instant.class, HarnessSessionEntryDTO.class.getDeclaredField("createdAt").getType());
    assertThrows(
        NoSuchFieldException.class,
        () -> HarnessSessionEntryDTO.class.getDeclaredField("createTime"));
  }

  @Test
  void threadProjectionUsesFinalEnumsAndAtFieldNames() throws Exception {
    HarnessThreadDTO thread =
        MAPPER.readValue(
            """
            {
              "threadId": "t-1",
              "parentThreadId": null,
              "name": "main",
              "sessionId": "s-1",
              "headEntryId": "e-1",
              "rootThreadId": "t-1",
              "yoloEnabled": false,
              "nextCommandSequence": "1",
              "version": "7",
              "status": "RUNNING",
              "executionControl": "STOPPED",
              "branchSettings": {}
            }
            """,
            HarnessThreadDTO.class);

    assertEquals(ThreadRuntimeStatus.RUNNING, thread.getStatus());
    assertEquals(ThreadExecutionControl.STOPPED, thread.getExecutionControl());
    assertNull(thread.getParentThreadId());
    assertEquals(Instant.class, HarnessThreadDTO.class.getDeclaredField("createdAt").getType());
    assertEquals(Instant.class, HarnessThreadDTO.class.getDeclaredField("updatedAt").getType());
    assertThrows(
        NoSuchFieldException.class, () -> HarnessThreadDTO.class.getDeclaredField("createTime"));
    assertThrows(
        NoSuchFieldException.class, () -> HarnessThreadDTO.class.getDeclaredField("updateTime"));
  }

  @Test
  void toolEnvironmentWaitFieldsStayExplicitlyNullable() throws Exception {
    assertEquals(
        String.class,
        ToolInvocationDTO.class.getDeclaredField("requiredEnvironmentName").getType());
    assertEquals(
        Instant.class,
        ToolInvocationDTO.class.getDeclaredField("environmentWaitFreshnessAt").getType());

    var json =
        NON_NULL_MAPPER.readTree(NON_NULL_MAPPER.writeValueAsString(new ToolInvocationDTO()));
    assertTrue(json.has("requiredEnvironmentName"));
    assertTrue(json.get("requiredEnvironmentName").isNull());
    assertTrue(json.has("environmentWaitFreshnessAt"));
    assertTrue(json.get("environmentWaitFreshnessAt").isNull());
    assertTrue(json.has("approvalJson"));
    assertTrue(json.get("approvalJson").isNull());
  }

  private static Set<String> subtypes(Class<?> union) {
    return Arrays.stream(union.getPermittedSubclasses())
        .map(type -> type.getAnnotation(JsonTypeName.class).value())
        .collect(Collectors.toSet());
  }
}
