package fun.fengwk.kkstudio.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrowsExactly;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import fun.fengwk.kkstudio.platform.account.StudioAccountService;
import fun.fengwk.kkstudio.platform.account.StudioRole;
import fun.fengwk.kkstudio.work.WorkDispatcherLifecycle;
import fun.fengwk.kkstudio.workspace.WorkspaceSchedules;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** End-to-end schedule HTTP coverage against the real PostgreSQL-backed workspace and agent. */
class WorkspaceScheduleHttpIntegrationTest extends WebPostgresTestSupport {
  @Autowired private WebApplicationContext context;
  @Autowired private StudioAccountFilter filter;
  @Autowired private ObjectMapper mapper;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private WorkspaceSchedules schedules;
  @Autowired private StudioAccountService accounts;
  @Autowired private PlatformTransactionManager transactions;

  @Autowired
  @Qualifier("scheduleLifecycle")
  private WorkDispatcherLifecycle scheduleLifecycle;

  @Autowired private WebAccountMockMvcTestConfiguration.AdminCredentials admin;
  private MockMvc mvc;
  private Cookie login;

  @BeforeEach
  void configure() {
    mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(filter).build();
    login = new Cookie("studio_session", admin.token());
  }

  /**
   * A paused schedule accepts a manual NEW_SESSION run, replays its key, and persists real output.
   */
  @Test
  void pausedNewSessionRunReplaysIdempotentlyAndStoresOutput() throws Exception {
    String space = createSpace();
    String id = createSchedule(space, false);
    checkHistory(space, id);
  }

  @Test
  void scheduleListPreviewAndPatchAreAvailableOverHttp() throws Exception {
    String space = createSpace();
    String id = createSchedule(space, false);

    mvc.perform(get(space + "/schedules").cookie(login))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].id").value(id));
    mvc.perform(
            post(space + "/schedules/preview")
                .cookie(login)
                .header("Origin", "http://localhost")
                .contentType("application/json")
                .content(
                    "{\"trigger\":{\"type\":\"ONCE\",\"localDate\":\"2099-01-01\",\"time\":\"12:00\"},\"timeZone\":\"UTC\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.next.length()").value(1));
    mvc.perform(
            patch(space + "/schedules/" + id)
                .cookie(login)
                .header("Origin", "http://localhost")
                .contentType("application/json")
                .content("{\"expectedVersion\":\"1\",\"title\":\"Updated schedule\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.title").value("Updated schedule"))
        .andExpect(jsonPath("$.version").value(2));
  }

  @Test
  void leaseLostAfterRunInsertRollsBackTheBusinessTransaction() throws Exception {
    scheduleLifecycle.stop();
    String space = createSpace();
    String id = createSchedule(space, true);
    UUID scheduleId = UUID.fromString(id);
    assertEquals(
        1,
        jdbc.update(
            "with due as (select date_trunc('minute', clock_timestamp()) as at), "
                + "updated_schedule as (update workspace_schedule set next_due_at=due.at, "
                + "trigger=trigger || jsonb_build_object('localDate', "
                + "to_char(due.at at time zone 'UTC','YYYY-MM-DD'),'time', "
                + "to_char(due.at at time zone 'UTC','HH24:MI')) from due where id=? "
                + "returning id,next_due_at) update durable_work dw set available_at=updated_schedule.next_due_at "
                + "from updated_schedule where dw.target_type='SCHEDULE' and dw.target_id=updated_schedule.id",
            scheduleId));
    Timestamp dueAt =
        jdbc.queryForObject(
            "select next_due_at from workspace_schedule where id=?", Timestamp.class, scheduleId);
    int sessionsBefore = jdbc.queryForObject("select count(*) from harness_session", Integer.class);
    int threadsBefore = jdbc.queryForObject("select count(*) from harness_thread", Integer.class);
    int commandsBefore =
        jdbc.queryForObject("select count(*) from harness_thread_command", Integer.class);
    var claim = schedules.claim().orElseThrow();
    String function = "lose_schedule_lease_" + UUID.randomUUID().toString().replace("-", "");
    jdbc.execute(
        "create function public."
            + function
            + "() returns trigger language plpgsql as $$"
            + """
            begin
              if new.result <> 'SUBMITTED' or not exists (
                select 1 from harness_thread_command where thread_id=new.output_thread_id
              ) then raise exception 'expected a submitted command before lease loss'; end if;
              perform pg_advisory_xact_lock(130, 7810);
              return new;
            end $$
            """);
    UUID replacement = UUID.randomUUID();
    try (var executor = Executors.newSingleThreadExecutor()) {
      jdbc.execute(
          "create trigger lose_schedule_lease before insert on workspace_schedule_run "
              + "for each row when (new.schedule_id = '"
              + id
              + "'::uuid) execute function public."
              + function
              + "()");
      var competitorSource =
          new DriverManagerDataSource(sharedJdbcUrl(), sharedJdbcUsername(), sharedJdbcPassword());
      var competitor = new JdbcTemplate(competitorSource);
      var worker =
          new TransactionTemplate(new JdbcTransactionManager(competitorSource))
              .execute(
                  status -> {
                    competitor.execute("select pg_advisory_xact_lock(130, 7810)");
                    var attempt = executor.submit(() -> schedules.auto(claim));
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                    while (!Boolean.TRUE.equals(
                        competitor.queryForObject(
                            """
            select exists (select 1 from pg_locks where locktype='advisory'
              and classid=130 and objid=7810 and objsubid=2 and not granted)
            """,
                            Boolean.class))) {
                      if (attempt.isDone()) {
                        try {
                          attempt.get();
                        } catch (ExecutionException ex) {
                          throw new AssertionError(
                              "worker failed before run insertion", ex.getCause());
                        } catch (InterruptedException ex) {
                          Thread.currentThread().interrupt();
                          throw new AssertionError(ex);
                        }
                        throw new AssertionError("worker returned before run insertion");
                      }
                      assertTrue(
                          System.nanoTime() < deadline, "worker did not reach run insertion");
                      Thread.yield();
                    }
                    assertEquals(
                        1,
                        competitor.update(
                            """
            update durable_work set lease_token=?, lease_until=statement_timestamp()+interval '2 minutes'
            where target_type='SCHEDULE' and target_id=? and lease_token=?
            """,
                            replacement,
                            scheduleId,
                            claim.leaseToken()));
                    return attempt;
                  });
      var failure =
          assertThrowsExactly(ExecutionException.class, () -> worker.get(5, TimeUnit.SECONDS));
      assertInstanceOf(IllegalArgumentException.class, failure.getCause());
    } finally {
      jdbc.execute("drop trigger if exists lose_schedule_lease on workspace_schedule_run");
      jdbc.execute("drop function if exists public." + function + "()");
    }
    assertEquals(
        dueAt,
        jdbc.queryForObject(
            "select next_due_at from workspace_schedule where id=?", Timestamp.class, scheduleId));
    assertEquals(
        dueAt,
        jdbc.queryForObject(
            "select available_at from durable_work where target_type='SCHEDULE' and target_id=?",
            Timestamp.class,
            scheduleId));
    assertEquals(
        replacement,
        jdbc.queryForObject(
            "select lease_token from durable_work where target_type='SCHEDULE' and target_id=?",
            UUID.class,
            scheduleId));
    assertEquals(
        0,
        jdbc.queryForObject(
            "select count(*) from workspace_schedule_run where schedule_id=?",
            Integer.class,
            scheduleId));
    assertEquals(
        0,
        jdbc.queryForObject(
            "select count(*) from workspace_resource where workspace_id=(select workspace_id from workspace_schedule where id=?) and session_id is not null",
            Integer.class,
            scheduleId));
    assertEquals(
        sessionsBefore, jdbc.queryForObject("select count(*) from harness_session", Integer.class));
    assertEquals(
        threadsBefore, jdbc.queryForObject("select count(*) from harness_thread", Integer.class));
    assertEquals(
        commandsBefore,
        jdbc.queryForObject("select count(*) from harness_thread_command", Integer.class));
  }

  @Test
  void onlyOneWorkerClaimsExpiredOccurrence() throws Exception {
    scheduleLifecycle.stop();
    String space = createSpace();
    String id = createSchedule(space, true);
    makeOverdue(id);
    try (var executor = Executors.newFixedThreadPool(2)) {
      var barrier = new CyclicBarrier(2);
      var a =
          executor.submit(
              () -> {
                barrier.await();
                return schedules.claim();
              });
      var b =
          executor.submit(
              () -> {
                barrier.await();
                return schedules.claim();
              });
      var first = a.get(5, TimeUnit.SECONDS);
      var second = b.get(5, TimeUnit.SECONDS);
      assertTrue(first.isPresent() != second.isPresent());
      var claim = first.orElseGet(second::orElseThrow);
      schedules.auto(claim);
      schedules.auto(claim);
    }
    mvc.perform(get(space + "/schedules/" + id + "/runs").cookie(login))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(1))
        .andExpect(jsonPath("$.items[0].reason").value("EXPIRED"));
    assertTrue(schedules.claim().isEmpty());
  }

  /** An outstanding claim cannot trigger while a disabling transaction holds the user lock. */
  @Test
  void disablingAccountsFencesClaimedWork() throws Exception {
    scheduleLifecycle.stop();
    UUID owner = accounts.create("schedule-owner", "schedule-password", StudioRole.USER).id();
    login =
        new Cookie("studio_session", accounts.login("schedule-owner", "schedule-password").token());
    String id = createSchedule(createSpace(), true);
    makeOverdue(id);
    var claim = schedules.claim().orElseThrow();
    try (var executor = Executors.newSingleThreadExecutor()) {
      var started = new CountDownLatch(1);
      var worker =
          new TransactionTemplate(transactions)
              .execute(
                  status -> {
                    accounts.update(owner, null, null, null, false);
                    var future =
                        executor.submit(
                            () -> {
                              started.countDown();
                              schedules.auto(claim);
                            });
                    try {
                      assertTrue(started.await(5, TimeUnit.SECONDS));
                    } catch (InterruptedException ex) {
                      Thread.currentThread().interrupt();
                      throw new AssertionError(ex);
                    }
                    return future;
                  });
      worker.get(5, TimeUnit.SECONDS);
    }
    accounts.update(owner, null, null, null, true);
    assertEquals(
        false,
        jdbc.queryForObject(
            "select enabled from workspace_schedule where id=?",
            Boolean.class,
            UUID.fromString(id)));
    assertEquals(
        0,
        jdbc.queryForObject(
            "select count(*) from workspace_schedule_run where schedule_id=?",
            Integer.class,
            UUID.fromString(id)));
    assertEquals(
        0,
        jdbc.queryForObject(
            "select count(*) from durable_work where target_type='SCHEDULE' and target_id=?",
            Integer.class,
            UUID.fromString(id)));
  }

  private void makeOverdue(String id) {
    jdbc.update(
        "update workspace_schedule set next_due_at='2000-01-01T00:00Z' where id=?",
        UUID.fromString(id));
    jdbc.update(
        "update durable_work set available_at='2000-01-01T00:00Z' where target_type='SCHEDULE' and target_id=?",
        UUID.fromString(id));
  }

  private String createSchedule(String space, boolean enabled) throws Exception {
    String folder =
        mapper
            .readTree(
                mvc.perform(get(space + "/tree").cookie(login))
                    .andExpect(status().isOk())
                    .andReturn()
                    .getResponse()
                    .getContentAsString())
            .at("/folders/0/id")
            .asText();
    String body =
        """
        {"title":"Integration schedule","message":"Reply with exactly: scheduled integration output","enabled":%s,
         "trigger":{"type":"ONCE","localDate":"2099-01-01","time":"12:00"},"timeZone":"UTC",
         "target":{"type":"NEW_SESSION","folderId":"%s","agentName":"default-assistant"}}
        """
            .formatted(enabled, folder);
    JsonNode created =
        mapper.readTree(
            mvc.perform(
                    post(space + "/schedules")
                        .cookie(login)
                        .header("Origin", "http://localhost")
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
    assertEquals(enabled, created.get("enabled").asBoolean());
    return created.get("id").asText();
  }

  private void checkHistory(String space, String id) throws Exception {
    String key = UUID.randomUUID().toString();
    JsonNode first = manualRun(space, id, key);
    assertEquals(first, manualRun(space, id, key));
    assertEquals("SUBMITTED", first.get("result").asText());
    String thread = first.at("/thread/id").asText();
    UUID threadId = UUID.fromString(thread);
    assertTrue(
        jdbc.queryForObject(
                "select count(*) from harness_thread_command where thread_id=? and payload::text like '%scheduled integration output%'",
                Integer.class, threadId)
            == 1);
    Instant base = Instant.parse("2024-01-01T00:00:00Z");
    for (int i = 0; i < 5; i++) {
      jdbc.update(
          "insert into workspace_schedule_run(id,schedule_id,occurrence_key,scheduled_at,time_zone,result) values(?,?,?,?,?,'SKIPPED')",
          UUID.randomUUID(),
          UUID.fromString(id),
          "pagination-" + i,
          Timestamp.from(base.plusSeconds(i)),
          "UTC");
    }
    JsonNode firstPage =
        mapper.readTree(
            mvc.perform(get(space + "/schedules/" + id + "/runs").cookie(login))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(5))
                .andExpect(jsonPath("$.olderCursor").isNotEmpty())
                .andReturn()
                .getResponse()
                .getContentAsString());
    String cursor = firstPage.get("olderCursor").asText();
    JsonNode secondPage =
        mapper.readTree(
            mvc.perform(
                    get(space + "/schedules/" + id + "/runs").param("before", cursor).cookie(login))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.olderCursor").doesNotExist())
                .andReturn()
                .getResponse()
                .getContentAsString());
    assertEquals(
        firstPage,
        mapper.readTree(
            mvc.perform(get(space + "/schedules/" + id + "/runs").cookie(login))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString()));
    assertEquals("SUBMITTED", firstPage.at("/items/0/result").asText());
  }

  private JsonNode manualRun(String space, String id, String key) throws Exception {
    return mapper.readTree(
        mvc.perform(
                post(space + "/schedules/" + id + "/runs")
                    .cookie(login)
                    .header("Origin", "http://localhost")
                    .header("Idempotency-Key", key))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString());
  }

  private String createSpace() throws Exception {
    JsonNode result =
        mapper.readTree(
            mvc.perform(
                    post("/api/chat-spaces")
                        .cookie(login)
                        .header("Origin", "http://localhost")
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType("application/json")
                        .content(
                            "{\"title\":\"Schedule workspace\",\"rootFolderName\":\"Root\",\"defaultAgentName\":\"default-assistant\"}"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
    return "/api/chat-spaces/" + result.get("id").asText();
  }
}
