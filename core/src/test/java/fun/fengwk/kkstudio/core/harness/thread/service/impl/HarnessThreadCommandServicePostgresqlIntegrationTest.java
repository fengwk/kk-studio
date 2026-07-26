package fun.fengwk.kkstudio.core.harness.thread.service.impl;

import static fun.fengwk.kkstudio.core.harness.persistence.postgresql.PostgresSchemaSupport.newConnection;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import fun.fengwk.kkstudio.core.harness.thread.command.TestThreads;
import fun.fengwk.kkstudio.core.persistence.test.PostgresSpringTestSupport;
import fun.fengwk.kkstudio.harness.runtime.configuration.RuntimeConfigSnapshot;
import fun.fengwk.kkstudio.harness.runtime.thread.RuntimeConfigInputPayload;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadCommandTransactions;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadInputPayloadJsonCodec;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadInputType;
import fun.fengwk.kkstudio.share.model.HarnessThreadAgentSetDTO;
import fun.fengwk.kkstudio.share.model.HarnessThreadInputDTO;
import fun.fengwk.kkstudio.share.model.HarnessThreadModelSetDTO;
import fun.fengwk.kkstudio.share.model.HarnessThreadYoloSetDTO;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;

/** 真实 command facade + PostgreSQL 对 immutable RuntimeConfig Input 的冻结回归。 */
class HarnessThreadCommandServicePostgresqlIntegrationTest extends PostgresSpringTestSupport {

  private static final long PROVIDER_ID = 80_001L;
  private static final long MODEL_A_ID = 80_002L;
  private static final long MODEL_B_ID = 80_003L;
  private static final long DEFINITION_ID = 80_004L;
  private static final ThreadInputPayloadJsonCodec INPUT_CODEC = new ThreadInputPayloadJsonCodec();

  @Autowired private ThreadCommandTransactions transactions;
  @Autowired private HarnessThreadCommandServiceImpl commandService;

  /** Queued config 不受 live resource mutation 影响，后续 SET_* 从 pending snapshot 复制。 */
  @Test
  void freezesQueuedAgentAndCopiesPendingSnapshotForModelAndYolo() throws Exception {
    seedResources();
    TestThreads.Bootstrapped boot =
        TestThreads.bootstrap(transactions, "snapshot", Instant.parse("2026-07-24T00:00:00Z"));
    long threadId = boot.threadId();
    long epoch = boot.executionEpoch();

    HarnessThreadAgentSetDTO agentRequest = new HarnessThreadAgentSetDTO();
    agentRequest.setAgentDefinitionId(Long.toString(DEFINITION_ID));
    agentRequest.setClientMessageId("agent-1");
    agentRequest.setExpectedExecutionEpoch(epoch);
    HarnessThreadInputDTO agentInput =
        commandService.queueAgent(Long.toString(threadId), agentRequest);
    RuntimeConfigSnapshot agentSnapshot = decode(agentInput, ThreadInputType.SET_AGENT);
    assertEquals("frozen-agent", agentSnapshot.agent().name());
    assertEquals("frozen prompt", agentSnapshot.agent().systemPrompt());
    assertEquals(MODEL_A_ID, agentSnapshot.model().descriptor().modelResourceId());

    invalidateLiveAgentAndModel();
    HarnessThreadAgentSetDTO retry = new HarnessThreadAgentSetDTO();
    retry.setAgentDefinitionId("deleted-or-invalid");
    retry.setClientMessageId("agent-1");
    retry.setExpectedExecutionEpoch(epoch);
    HarnessThreadInputDTO retried = commandService.queueAgent(Long.toString(threadId), retry);
    assertEquals(agentInput.getInputId(), retried.getInputId());
    assertEquals(agentInput.getPayloadJson(), retried.getPayloadJson());
    assertEquals(agentSnapshot, readPersistedSnapshot(agentInput));

    HarnessThreadModelSetDTO modelRequest = new HarnessThreadModelSetDTO();
    modelRequest.setModelId(Long.toString(MODEL_B_ID));
    modelRequest.setVariant("fast");
    modelRequest.setClientMessageId("model-1");
    modelRequest.setExpectedExecutionEpoch(epoch);
    HarnessThreadInputDTO modelInput =
        commandService.queueModel(Long.toString(threadId), modelRequest);
    RuntimeConfigSnapshot modelSnapshot = decode(modelInput, ThreadInputType.SET_MODEL);
    assertEquals(agentSnapshot.agent(), modelSnapshot.agent());
    assertEquals(agentSnapshot.tools(), modelSnapshot.tools());
    assertEquals(agentSnapshot.skills(), modelSnapshot.skills());
    assertEquals(agentSnapshot.yoloEnabled(), modelSnapshot.yoloEnabled());
    assertEquals(MODEL_B_ID, modelSnapshot.model().descriptor().modelResourceId());
    assertEquals("fast", modelSnapshot.model().variant().id());

    HarnessThreadYoloSetDTO yoloRequest = new HarnessThreadYoloSetDTO();
    yoloRequest.setYoloEnabled(!modelSnapshot.yoloEnabled());
    yoloRequest.setClientMessageId("yolo-1");
    yoloRequest.setExpectedExecutionEpoch(epoch);
    HarnessThreadInputDTO yoloInput =
        commandService.queueYolo(Long.toString(threadId), yoloRequest);
    RuntimeConfigSnapshot yoloSnapshot = decode(yoloInput, ThreadInputType.SET_YOLO);
    assertEquals(modelSnapshot.agent(), yoloSnapshot.agent());
    assertEquals(modelSnapshot.model(), yoloSnapshot.model());
    assertEquals(modelSnapshot.tools(), yoloSnapshot.tools());
    assertEquals(modelSnapshot.skills(), yoloSnapshot.skills());
    assertEquals(!modelSnapshot.yoloEnabled(), yoloSnapshot.yoloEnabled());
    assertEquals(1L, agentInput.getSequence());
    assertEquals(2L, modelInput.getSequence());
    assertEquals(3L, yoloInput.getSequence());
  }

  private static RuntimeConfigSnapshot decode(HarnessThreadInputDTO input, ThreadInputType type) {
    RuntimeConfigInputPayload payload =
        assertInstanceOf(
            RuntimeConfigInputPayload.class, INPUT_CODEC.decode(type, input.getPayloadJson()));
    return payload.snapshot();
  }

  private static RuntimeConfigSnapshot readPersistedSnapshot(HarnessThreadInputDTO input)
      throws SQLException {
    try (Connection connection = newConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "select payload::text from harness_thread_input where id = ?")) {
      statement.setLong(1, Long.parseLong(input.getInputId()));
      try (var result = statement.executeQuery()) {
        if (!result.next()) {
          throw new AssertionError("persisted input is missing");
        }
        return ((RuntimeConfigInputPayload)
                INPUT_CODEC.decode(ThreadInputType.SET_AGENT, result.getString(1)))
            .snapshot();
      }
    }
  }

  private static void seedResources() throws SQLException {
    try (Connection connection = newConnection();
        Statement statement = connection.createStatement()) {
      statement.executeUpdate(
          "insert into agent_provider (id, name, provider_type, config) values ("
              + PROVIDER_ID
              + ", 'provider', 'openai', '{}'::jsonb)");
      statement.executeUpdate(
          "insert into agent_model (id, provider_id, name, description, config) values ("
              + MODEL_A_ID
              + ", "
              + PROVIDER_ID
              + ", 'model-a', 'Model A', '"
              + modelConfig("default")
              + "'::jsonb)");
      statement.executeUpdate(
          "insert into agent_model (id, provider_id, name, description, config) values ("
              + MODEL_B_ID
              + ", "
              + PROVIDER_ID
              + ", 'model-b', 'Model B', '"
              + modelConfig("fast")
              + "'::jsonb)");
      statement.executeUpdate(
          "insert into agent_definition (id, name, system_prompt, model_id, variant, config) values ("
              + DEFINITION_ID
              + ", 'frozen-agent', 'frozen prompt', "
              + MODEL_A_ID
              + ", 'default', '"
              + definitionConfig()
              + "'::jsonb)");
    }
  }

  private static void invalidateLiveAgentAndModel() throws SQLException {
    try (Connection connection = newConnection();
        Statement statement = connection.createStatement()) {
      assertEquals(
          1,
          statement.executeUpdate(
              "update agent_definition set name = 'mutated-agent', system_prompt = 'mutated',"
                  + " config = '{}'::jsonb, version = version + 1 where id = "
                  + DEFINITION_ID));
      assertEquals(
          1,
          statement.executeUpdate(
              "update agent_model set name = 'mutated-model-a', config = '{}'::jsonb,"
                  + " version = version + 1 where id = "
                  + MODEL_A_ID));
    }
  }

  private static String definitionConfig() {
    return """
        {"tools":[],"skills":[],"allowedSubagents":[],"executionPolicy":{"maxTurns":8,"maxDepth":3,"maxDirectSubagents":2,"maxTotalSubagents":4}}
        """
        .trim();
  }

  private static String modelConfig(String defaultVariant) {
    return ("""
        {"limit":{"context":32768,"output":4096},"abilities":{"tools":true,"reasoning":false,"inputModalities":["TEXT"]},"defaultVariant":"%s","variants":[{"id":"default"},{"id":"fast"}],"pricing":{"currency":"USD","pricingTier":"test","serviceTier":"default","serviceTierMultiplier":1,"version":"v1","inputPerMillionTokens":0,"outputPerMillionTokens":0,"cacheReadPerMillionTokens":0,"cacheWritePerMillionTokens":0,"cacheWriteLongPerMillionTokens":0,"reasoningPerMillionTokens":0}}
        """)
        .formatted(defaultVariant)
        .trim();
  }
}
