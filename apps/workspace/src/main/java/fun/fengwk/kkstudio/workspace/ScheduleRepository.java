package fun.fengwk.kkstudio.workspace;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import fun.fengwk.kkstudio.common.json.StrictJson;
import fun.fengwk.kkstudio.platform.harness.store.HarnessPlacementQuery;
import fun.fengwk.kkstudio.platform.orchestration.ResourceOwnership.Kind;
import fun.fengwk.kkstudio.workspace.dto.ScheduleDTO;
import fun.fengwk.kkstudio.workspace.dto.ScheduleRunDTO;
import fun.fengwk.kkstudio.workspace.dto.ScheduleRunPageDTO;
import fun.fengwk.kkstudio.workspace.dto.ScheduleRunThreadDTO;
import fun.fengwk.kkstudio.workspace.dto.ScheduleTargetDTO;
import fun.fengwk.kkstudio.workspace.dto.ScheduleTriggerDTO;

import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Persistence boundary for schedules and their run history. */
@Repository
class ScheduleRepository {
  private final JdbcClient jdbc;

  private final HarnessPlacementQuery harness;
  private final WorkspacePlacements placements;

  ScheduleRepository(
      JdbcClient jdbc, HarnessPlacementQuery harness, WorkspacePlacements placements) {
    this.jdbc = jdbc;
    this.harness = harness;
    this.placements = placements;
  }

  private ScheduleRunThreadDTO link(UUID space, UUID output) {
    if (output == null) {
      return null;
    }
    return harness
        .thread(output)
        .filter(
            thread ->
                placements
                    .find(space, thread.sessionId())
                    .filter(edge -> edge.kind() == Kind.SESSION)
                    .isPresent())
        .map(thread -> new ScheduleRunThreadDTO(thread.sessionId(), thread.id(), thread.title()))
        .orElse(null);
  }

  private ScheduleDTO map(ResultSet r, int n) throws SQLException {
    var target =
        new ScheduleTargetDTO(
            ScheduleTargetDTO.Type.valueOf(r.getString("target_type")),
            r.getObject("folder_id", UUID.class),
            r.getString("agent_name"),
            r.getObject("session_id", UUID.class),
            r.getObject("target_type", String.class).equals("BRANCH")
                ? r.getObject("thread_id", UUID.class)
                : null,
            r.getObject("turn_end_id", UUID.class),
            r.getObject("target_type", String.class).equals("CONTINUE")
                ? r.getObject("thread_id", UUID.class)
                : null);
    return new ScheduleDTO(
        r.getObject("id", UUID.class),
        r.getString("title"),
        r.getString("message"),
        r.getBoolean("enabled"),
        StrictJson.standard()
            .read(r.getString("trigger"), ScheduleTriggerDTO.class, "schedule trigger"),
        r.getString("time_zone"),
        target,
        instant(r, "next_due_at"),
        r.getLong("version"));
  }

  private static Instant instant(ResultSet result, String column) throws SQLException {
    return instant(result.getObject(column, OffsetDateTime.class));
  }

  private static Instant instant(OffsetDateTime value) {
    return value == null ? null : value.toInstant();
  }

  private static OffsetDateTime offset(Instant value) {
    return value == null ? null : value.atOffset(ZoneOffset.UTC);
  }

  private static final String COLUMNS =
      "id,title,message,enabled,trigger::text trigger,time_zone,next_due_at,version,target_type,folder_id,agent_name,session_id,thread_id,turn_end_id";

  ScheduleDTO get(UUID space, UUID id, boolean lock) {
    return jdbc.sql(
            "select "
                + COLUMNS
                + " from workspace_schedule where workspace_id=:s and id=:i"
                + (lock ? " for update" : ""))
        .param("s", space)
        .param("i", id)
        .query(this::map)
        .optional()
        .orElseThrow(ChatSpaceService::resourceNotFound);
  }

  Optional<UUID> spaceOf(UUID id) {
    return jdbc.sql("select workspace_id from workspace_schedule where id=:i")
        .param("i", id)
        .query(UUID.class)
        .optional();
  }

  Optional<String> creationHash(UUID space, UUID id) {
    return jdbc.sql(
            "select create_request_hash from workspace_schedule where workspace_id=:s and id=:i")
        .param("s", space)
        .param("i", id)
        .query(String.class)
        .optional();
  }

  List<ScheduleDTO> list(UUID space) {
    return jdbc.sql(
            "select " + COLUMNS + " from workspace_schedule where workspace_id=:s order by id")
        .param("s", space)
        .query(this::map)
        .list();
  }

  void insert(UUID space, ScheduleDTO d, String hash) {
    var t = d.target();
    jdbc.sql(
            "insert into workspace_schedule(id,workspace_id,title,message,enabled,trigger,time_zone,next_due_at,version,create_request_hash,target_type,folder_id,agent_name,session_id,thread_id,turn_end_id) values(:id,:s,:title,:message,:enabled,cast(:trigger as jsonb),:zone,:due,:v,:hash,:type,:folder,:agent,:session,:thread,:turn)")
        .param("id", d.id())
        .param("s", space)
        .param("title", d.title())
        .param("message", d.message())
        .param("enabled", d.enabled())
        .param("trigger", StrictJson.standard().write(d.trigger(), "schedule trigger"))
        .param("zone", d.timeZone())
        .param("due", offset(d.nextDueAt()))
        .param("v", d.version())
        .param("hash", hash)
        .param("type", t.type().name())
        .param("folder", t.folderId())
        .param("agent", t.agentName())
        .param("session", t.sessionId())
        .param(
            "thread",
            t.type() == ScheduleTargetDTO.Type.NEW_SESSION
                ? null
                : t.type() == ScheduleTargetDTO.Type.BRANCH ? t.sourceThreadId() : t.threadId())
        .param("turn", t.turnEndEntryId())
        .update();
  }

  void update(UUID space, ScheduleDTO d) {
    var t = d.target();
    jdbc.sql(
            "update workspace_schedule set title=:title,message=:message,enabled=:enabled,trigger=cast(:trigger as jsonb),time_zone=:zone,next_due_at=:due,version=:v,target_type=:type,folder_id=:folder,agent_name=:agent,session_id=:session,thread_id=:thread,turn_end_id=:turn where workspace_id=:s and id=:id")
        .param("title", d.title())
        .param("message", d.message())
        .param("enabled", d.enabled())
        .param("trigger", StrictJson.standard().write(d.trigger(), "schedule trigger"))
        .param("zone", d.timeZone())
        .param("due", offset(d.nextDueAt()))
        .param("v", d.version())
        .param("type", t.type().name())
        .param("folder", t.folderId())
        .param("agent", t.agentName())
        .param("session", t.sessionId())
        .param(
            "thread",
            t.type() == ScheduleTargetDTO.Type.NEW_SESSION
                ? null
                : t.type() == ScheduleTargetDTO.Type.BRANCH ? t.sourceThreadId() : t.threadId())
        .param("turn", t.turnEndEntryId())
        .param("s", space)
        .param("id", d.id())
        .update();
  }

  void delete(UUID space, UUID id) {
    jdbc.sql("delete from workspace_schedule where workspace_id=:s and id=:i")
        .param("s", space)
        .param("i", id)
        .update();
  }

  Instant now() {
    return instant(
        jdbc.sql("select date_trunc('milliseconds',statement_timestamp())")
            .query(OffsetDateTime.class)
            .single());
  }

  List<UUID> lockSpaces(UUID owner) {
    return jdbc.sql("select id from chat_space where owner_user_id=:o order by id for update")
        .param("o", owner)
        .query(UUID.class)
        .list();
  }

  List<UUID> lockEnabled(UUID space) {
    return jdbc.sql(
            "select id from workspace_schedule where workspace_id=:s and enabled order by id for update")
        .param("s", space)
        .query(UUID.class)
        .list();
  }

  UUID ownerOf(UUID space) {
    return jdbc.sql("select owner_user_id from chat_space where id=:s")
        .param("s", space)
        .query(UUID.class)
        .single();
  }

  void pause(UUID schedule) {
    jdbc.sql(
            "update workspace_schedule set enabled=false,next_due_at=null,version=version+1 where id=:i")
        .param("i", schedule)
        .update();
  }

  void advance(UUID schedule, Instant due) {
    jdbc.sql("update workspace_schedule set next_due_at=:d where id=:i")
        .param("d", offset(due))
        .param("i", schedule)
        .update();
  }

  Optional<ScheduleRunDTO> findRun(UUID space, UUID schedule, String key) {
    return jdbc.sql(
            "select r.id,r.scheduled_at,r.started_at,r.time_zone,r.result,r.reason,r.output_thread_id from workspace_schedule_run r join workspace_schedule s on s.id=r.schedule_id where s.workspace_id=:space and r.schedule_id=:schedule and r.occurrence_key=:key")
        .param("space", space)
        .param("schedule", schedule)
        .param("key", key)
        .query(
            (r, n) ->
                new ScheduleRunDTO(
                    r.getObject("id", UUID.class),
                    instant(r, "scheduled_at"),
                    instant(r, "started_at"),
                    r.getString("time_zone"),
                    ScheduleRunDTO.Result.valueOf(r.getString("result")),
                    r.getString("reason"),
                    link(space, r.getObject("output_thread_id", UUID.class))))
        .optional();
  }

  Optional<UUID> lastOutput(UUID schedule) {
    return jdbc.sql(
            "select output_thread_id from workspace_schedule_run where schedule_id=:s and result='SUBMITTED' order by started_at desc nulls last,id desc limit 1")
        .param("s", schedule)
        .query(UUID.class)
        .optional();
  }

  ScheduleRunPageDTO runs(UUID space, UUID schedule, String before) {
    UUID cursorId = null;
    Instant cursorAt = null;
    if (before != null) {
      try {
        String[] parts =
            new String(Base64.getUrlDecoder().decode(before), StandardCharsets.UTF_8)
                .split("\\|", -1);
        if (parts.length != 3 || !schedule.toString().equals(parts[0])) {
          throw WorkspaceInput.invalid("Invalid schedule run cursor");
        }
        cursorAt = Instant.ofEpochMilli(Long.parseLong(parts[1]));
        cursorId = WorkspaceInput.uuid(parts[2]);
      } catch (IllegalArgumentException ex) {
        throw WorkspaceInput.invalid("Invalid schedule run cursor");
      }
    }
    var q =
        jdbc.sql(
                "select r.id,r.scheduled_at,r.started_at,r.time_zone,r.result,r.reason,r.output_thread_id from workspace_schedule_run r join workspace_schedule s on s.id=r.schedule_id where s.workspace_id=:space and r.schedule_id=:schedule and (cast(:at as timestamptz) is null or (r.scheduled_at,r.id)<(cast(:at as timestamptz),:id)) order by r.scheduled_at desc,r.id desc limit 6")
            .param("space", space)
            .param("schedule", schedule)
            .param("at", offset(cursorAt))
            .param("id", cursorId);
    List<ScheduleRunDTO> rows =
        q.query(
                (r, n) ->
                    new ScheduleRunDTO(
                        r.getObject("id", UUID.class),
                        instant(r, "scheduled_at"),
                        instant(r, "started_at"),
                        r.getString("time_zone"),
                        ScheduleRunDTO.Result.valueOf(r.getString("result")),
                        r.getString("reason"),
                        link(space, r.getObject("output_thread_id", UUID.class))))
            .list();
    boolean more = rows.size() > 5;
    if (more) {
      rows = rows.subList(0, 5);
    }
    String next = null;
    if (more && !rows.isEmpty()) {
      var last = rows.getLast();
      next =
          Base64.getUrlEncoder()
              .withoutPadding()
              .encodeToString(
                  (schedule + "|" + last.scheduledAt().toEpochMilli() + "|" + last.id())
                      .getBytes(StandardCharsets.UTF_8));
    }
    return new ScheduleRunPageDTO(rows, next);
  }

  void insertRun(UUID schedule, String key, ScheduleRunDTO run, UUID output) {
    jdbc.sql(
            "insert into workspace_schedule_run(id,schedule_id,occurrence_key,scheduled_at,started_at,time_zone,result,reason,output_thread_id) values(:id,:s,:key,:at,:started,:zone,:result,:reason,:output)")
        .param("id", run.id())
        .param("s", schedule)
        .param("key", key)
        .param("at", offset(run.scheduledAt()))
        .param("started", offset(run.startedAt()))
        .param("zone", run.timeZone())
        .param("result", run.result().name())
        .param("reason", run.reason())
        .param("output", output)
        .update();
  }
}
