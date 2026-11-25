package fun.fengwk.kkstudio.platform.harness.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
class HarnessHttpDtoContractTest {

  private static final JsonMapper MAPPER = JsonMapper.builder().build();

  private static final JsonMapper STRICT_MAPPER =
      JsonMapper.builder().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();

  private static final JsonMapper NON_NULL_MAPPER =
      JsonMapper.builder()
          .changeDefaultPropertyInclusion(
              inclusion -> inclusion.withValueInclusion(JsonInclude.Include.NON_NULL))
          .build();

  @Test
  void sealedUnionsExposeOnlyFinalVariants() {
    assertTrue(HarnessCommandCreateDTO.class.isSealed());
    assertTrue(HarnessUserMessageContentDTO.class.isSealed());
    assertEquals(
        Set.of("USER_MESSAGE", "SET_EXECUTION", "SET_ENVIRONMENT"),
        subtypes(HarnessCommandCreateDTO.class));
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
              "payload": {},
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
    assertEquals(1L, thread.getNextCommandSequence());
    assertEquals(7L, thread.getVersion());
    assertEquals(
        Long.class, HarnessThreadDTO.class.getDeclaredField("nextCommandSequence").getType());
    assertEquals(Long.class, HarnessThreadDTO.class.getDeclaredField("version").getType());
    assertEquals(Instant.class, HarnessThreadDTO.class.getDeclaredField("createdAt").getType());
    assertEquals(Instant.class, HarnessThreadDTO.class.getDeclaredField("updatedAt").getType());
    assertThrows(
        NoSuchFieldException.class, () -> HarnessThreadDTO.class.getDeclaredField("createTime"));
    assertThrows(
        NoSuchFieldException.class, () -> HarnessThreadDTO.class.getDeclaredField("updateTime"));
  }

  @Test
  void threadRuntimeStatusWireIncludesExitPending() throws Exception {
    HarnessThreadDTO thread =
        MAPPER.readValue(
            """
            {
              "threadId": "t-1",
              "name": "main",
              "sessionId": "s-1",
              "headEntryId": "e-1",
              "rootThreadId": "t-1",
              "yoloEnabled": false,
              "nextCommandSequence": "1",
              "version": "7",
              "status": "EXIT_PENDING",
              "executionControl": "STOPPED"
            }
            """,
            HarnessThreadDTO.class);
    assertEquals(ThreadRuntimeStatus.EXIT_PENDING, thread.getStatus());
    assertEquals(
        Set.of("RUNNING", "EXIT_PENDING", "WAITING", "IDLE"),
        Arrays.stream(ThreadRuntimeStatus.values())
            .map(status -> status.name())
            .collect(Collectors.toSet()));
  }

  @Test
  void nullableToolSnapshotFieldsStayExplicitlyPresent() throws Exception {
    assertEquals(String.class, ToolInvocationDTO.class.getDeclaredField("childThreadId").getType());
    assertEquals(
        String.class,
        ToolInvocationDTO.class.getDeclaredField("requiredEnvironmentName").getType());
    assertEquals(
        Instant.class,
        ToolInvocationDTO.class.getDeclaredField("environmentWaitFreshnessAt").getType());

    var json =
        NON_NULL_MAPPER.readTree(NON_NULL_MAPPER.writeValueAsString(new ToolInvocationDTO()));
    assertTrue(json.has("childThreadId"));
    assertTrue(json.get("childThreadId").isNull());
    assertTrue(json.has("requiredEnvironmentName"));
    assertTrue(json.get("requiredEnvironmentName").isNull());
    assertTrue(json.has("environmentWaitFreshnessAt"));
    assertTrue(json.get("environmentWaitFreshnessAt").isNull());
    assertTrue(json.has("approval"));
    assertTrue(json.get("approval").isNull());
  }

  private static Set<String> subtypes(Class<?> union) {
    return Arrays.stream(union.getPermittedSubclasses())
        .map(type -> type.getAnnotation(JsonTypeName.class).value())
        .collect(Collectors.toSet());
  }
}
