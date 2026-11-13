package fun.fengwk.kkstudio.platform.harness.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import fun.fengwk.kkstudio.harness.api.entry.BranchSettings;
import fun.fengwk.kkstudio.harness.api.entry.ModelSelection;
import fun.fengwk.kkstudio.harness.api.entry.TurnStartReason;
import fun.fengwk.kkstudio.harness.api.history.Entry;
import fun.fengwk.kkstudio.harness.api.history.EntryPath;
import fun.fengwk.kkstudio.harness.api.history.RootPayload;
import fun.fengwk.kkstudio.harness.api.history.TurnStartPayload;
import fun.fengwk.kkstudio.harness.builtin.driver.BuiltinExecution;
import fun.fengwk.kkstudio.harness.builtin.model.ModelRequestMaterializer;
import fun.fengwk.kkstudio.harness.builtin.port.store.ModelRequestSpec;
import fun.fengwk.kkstudio.harness.builtin.port.store.ModelRequestSpecJsonCodec;
import fun.fengwk.kkstudio.harness.model.cache.PromptCacheRetention;
import fun.fengwk.kkstudio.harness.model.cache.ProviderCacheControl;
import fun.fengwk.kkstudio.platform.harness.thread.command.DatabaseTurnResolver;
import fun.fengwk.kkstudio.platform.harness.thread.command.LiveTurnPlan;
import fun.fengwk.kkstudio.platform.persistence.test.PostgresSpringTestSupport;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** PostgreSQL 上的已冻结调用不应跟随模型目录的后续变更。 */
class FrozenModelCachePostgresIntegrationTest extends PostgresSpringTestSupport {

  @Autowired private JdbcTemplate jdbc;
  @Autowired private DatabaseTurnResolver resolver;

  @Test
  void inFlightInvocationRetainsFrozenVariantAfterModelConfigChanges() {
    UUID session = UUID.randomUUID();
    UUID thread = UUID.randomUUID();
    UUID rootId = UUID.randomUUID();
    UUID turnId = UUID.randomUUID();
    UUID invocationId = UUID.randomUUID();
    jdbc.update(
        "insert into provider (name, provider_type, base_url, config)"
            + " values ('cache-provider', 'openai', 'https://example.invalid/v1', '{}'::jsonb)");
    jdbc.update(
        "insert into model (name, kind, provider_name, model_id, config)"
            + " values ('cache-model', 'MODEL', 'cache-provider', 'wire-model', ?::jsonb)",
        config("SHORT"));
    jdbc.update(
        "insert into agent (name, description, system_prompt, execution, config)"
            + " values ('cache-agent', '', 'system',"
            + " '{\"driver\":\"builtin\",\"settings\":{\"modelName\":\"cache-model\",\"variant\":\"default\"}}'::jsonb,"
            + " '{\"tools\":[],\"skills\":[],\"subagents\":[]}'::jsonb)");

    BranchSettings settings =
        new BranchSettings(
            "cache-agent",
            BuiltinExecution.of(ModelSelection.model("cache-model", "default")),
            null);
    Instant now = Instant.parse("2026-01-01T00:00:00Z");
    Entry root = new Entry(rootId, session, null, new RootPayload(settings), now);
    Entry turn =
        new Entry(
            turnId,
            session,
            rootId,
            new TurnStartPayload(TurnStartReason.INPUT, settings, thread),
            now);
    EntryPath path = new EntryPath(List.of(root, turn));
    ModelRequestSpec frozen =
        assertInstanceOf(LiveTurnPlan.Planned.class, resolver.planLive(thread, session, path))
            .spec();
    assertEquals(PromptCacheRetention.SHORT, frozen.variant().promptCacheRetention());

    // Persist the accepted invocation before changing the catalog, then read its durable snapshot.
    jdbc.update(
        "insert into harness_session (id,name,created_at) values (?, 'cache', now())", session);
    jdbc.update(
        "insert into harness_entry (id,session_id,entry_type,payload,created_at)"
            + " values (?,?,'ROOT','{}'::jsonb,now())",
        rootId,
        session);
    jdbc.update(
        "insert into harness_entry (id,session_id,parent_entry_id,entry_type,payload,created_at)"
            + " values (?,?,?,'TURN_START','{}'::jsonb,now())",
        turnId,
        session,
        rootId);
    jdbc.update(
        "insert into harness_thread (id,session_id,head_entry_id,creation_request_hash,name,"
            + "root_thread_id,kind,constraints,execution_control,next_command_sequence,"
            + "version,created_at,updated_at) values (?,?,?,repeat('a',64),'cache',?,'USER',"
            + "'{\"maxOutputTokens\":null,\"allowAutoCompaction\":true}'::jsonb,"
            + "'RUNNABLE',1,0,now(),now())",
        thread,
        session,
        turnId,
        thread);
    ModelRequestSpecJsonCodec codec = new ModelRequestSpecJsonCodec();
    jdbc.update(
        "insert into builtin_model_invocation (id,thread_id,turn_start_entry_id,"
            + "request_head_entry_id,request_spec,status,attempt,failed_attempts,created_at,updated_at)"
            + " values (?,?,?,?,?::jsonb,'RUNNING',1,'[]'::jsonb,now(),now())",
        invocationId,
        thread,
        turnId,
        turnId,
        codec.encode(frozen));
    jdbc.update("update model set config = ?::jsonb where name = 'cache-model'", config("LONG"));

    ModelRequestSpec durable =
        codec.decode(
            jdbc.queryForObject(
                "select request_spec::text from builtin_model_invocation where id = ?",
                String.class,
                invocationId));
    assertEquals(frozen.variant(), durable.variant());
    assertEquals(
        ProviderCacheControl.session(PromptCacheRetention.SHORT, session.toString()),
        new ModelRequestMaterializer().materialize(path, durable).cacheControl());
    ModelRequestSpec next =
        assertInstanceOf(LiveTurnPlan.Planned.class, resolver.planLive(thread, session, path))
            .spec();
    assertEquals(PromptCacheRetention.LONG, next.variant().promptCacheRetention());
  }

  private static String config(String retention) {
    return "{\"limit\":{\"context\":32768,\"output\":4096},"
        + "\"abilities\":{\"tools\":false,\"reasoning\":false,\"inputModalities\":[\"TEXT\"]},"
        + "\"defaultVariant\":\"default\",\"variants\":[{\"id\":\"default\","
        + "\"promptCacheRetention\":\""
        + retention
        + "\"}]}";
  }
}
