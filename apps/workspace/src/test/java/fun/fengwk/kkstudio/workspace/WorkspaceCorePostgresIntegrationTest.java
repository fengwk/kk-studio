package fun.fengwk.kkstudio.workspace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import fun.fengwk.kkstudio.canvas.CanvasDocument;
import fun.fengwk.kkstudio.canvas.infra.postgresql.CanvasQueryService;
import fun.fengwk.kkstudio.common.error.DomainException;
import fun.fengwk.kkstudio.harness.runtime.HarnessRuntime;
import fun.fengwk.kkstudio.platform.account.StudioPrincipal;
import fun.fengwk.kkstudio.platform.account.StudioRole;
import fun.fengwk.kkstudio.platform.account.StudioUserLock;
import fun.fengwk.kkstudio.platform.harness.dto.HarnessUserMessageContentDTO.TextDTO;
import fun.fengwk.kkstudio.platform.harness.store.HarnessPlacementQuery;
import fun.fengwk.kkstudio.platform.orchestration.HarnessCommandAcceptanceOrchestrator;
import fun.fengwk.kkstudio.platform.orchestration.IdleThreadOrchestrator;
import fun.fengwk.kkstudio.platform.orchestration.ResourceOwnership.Kind;
import fun.fengwk.kkstudio.platform.orchestration.SessionDeletionOrchestrator;
import fun.fengwk.kkstudio.platform.orchestration.SessionInitialSettings;
import fun.fengwk.kkstudio.workspace.dto.SessionCreateDTO;
import fun.fengwk.kkstudio.workspace.dto.WorkspaceResourceKind;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * PostgreSQL-backed workspace service coverage; only Harness and Canvas reads are external fakes.
 */
@Testcontainers
class WorkspaceCorePostgresIntegrationTest {
  @Container
  private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

  @BeforeAll
  static void migrate() {
    Flyway.configure()
        .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
        .load()
        .migrate();
  }

  @Test
  void treeBuildsFolderHierarchyAndLoadsPlacedCanvasFromPostgres() {
    var jdbc = jdbc();
    var owner = testUser(jdbc, "tree-owner");
    var spaces = new ChatSpaceService(jdbc, new StudioUserLock(jdbc));
    var space =
        spaces.create(
            owner,
            UUID.randomUUID(),
            new ChatSpaceService.Create("Tree", "Root", null, null, false));
    var folders = new FolderService(spaces, jdbc);
    var root =
        jdbc.sql("select id from workspace_folder where workspace_id = :space")
            .param("space", space.id())
            .query(UUID.class)
            .single();
    var child =
        folders.create(
            owner, space.id(), UUID.randomUUID(), new FolderService.Create(root, "Nested", 0));
    UUID canvas = UUID.randomUUID();
    jdbc.sql("insert into canvas_document (id, title) values (:id, 'Canvas title')")
        .param("id", canvas)
        .update();
    var placements = new WorkspacePlacements(jdbc);
    placements.insert(
        space.id(),
        child.id(),
        canvas,
        Kind.CANVAS,
        null,
        WorkspacePlacements.fingerprint("canvas"),
        1,
        null);
    var document = new CanvasDocument(canvas, "Canvas title", 0, Instant.EPOCH, Instant.EPOCH);
    var canvases = mock(CanvasQueryService.class);
    when(canvases.findDocuments(List.of(canvas))).thenReturn(List.of(document));
    var harness = mock(HarnessPlacementQuery.class);
    var tree =
        new WorkspaceTreeService(spaces, placements, harness, runtimeProvider(), canvases, jdbc);

    var result = tree.tree(owner, space.id());

    assertEquals("1", result.treeVersion());
    assertEquals("Root", result.folders().getFirst().name());
    var nested = result.folders().getFirst().children().getFirst();
    assertEquals(child.id(), nested.id());
    assertEquals(WorkspaceResourceKind.CANVAS, nested.resources().getFirst().kind());
    assertEquals(canvas, nested.resources().getFirst().id());
    assertEquals("Canvas title", nested.resources().getFirst().title());
    assertNull(nested.resources().getFirst().threads());
    verify(harness).sessions(List.of());
    verify(harness).threads(List.of());
  }

  @Test
  void createReplaysPersistedSessionAndReturnsOriginalCreationResult() {
    var jdbc = jdbc();
    var owner = testUser(jdbc, "session-replay-owner");
    var spaces = new ChatSpaceService(jdbc, new StudioUserLock(jdbc));
    var space =
        spaces.create(
            owner,
            UUID.randomUUID(),
            new ChatSpaceService.Create("Replay", "Root", null, null, false));
    UUID key = UUID.randomUUID();
    UUID sessionId = ChatSpaceService.derivedId("session", space.id(), key);
    UUID threadId = ChatSpaceService.derivedId("main", space.id(), key);
    UUID commandId = UUID.randomUUID();
    String agent = "test-agent-" + UUID.randomUUID();
    jdbc.sql("insert into agent (name, config) values (:name, '{}'::jsonb)")
        .param("name", agent)
        .update();
    jdbc.sql(
            "insert into harness_session (id, name, created_at) "
                + "values (:id, 'Persisted', current_timestamp)")
        .param("id", sessionId)
        .update();
    var request =
        new SessionCreateDTO(null, List.of(new TextDTO("Initial prompt")), null, null, null);
    var placements = new WorkspacePlacements(jdbc);
    placements.insert(
        space.id(),
        placements.firstRootFolder(space.id()),
        sessionId,
        Kind.SESSION,
        agent,
        WorkspacePlacements.fingerprint(request),
        1,
        commandId);
    var acceptance = mock(HarnessCommandAcceptanceOrchestrator.class);
    var settings = mock(SessionInitialSettings.class);
    var service =
        new WorkspaceSessions(
            spaces,
            jdbc,
            placements,
            mock(HarnessPlacementQuery.class),
            mock(WorkspaceTreeService.class),
            runtimeProvider(),
            acceptance,
            mock(IdleThreadOrchestrator.class),
            mock(SessionDeletionOrchestrator.class),
            settings);

    var first = service.create(owner, space.id(), key, request);
    var replay = service.create(owner, space.id(), key, request);
    var reusedKeyError =
        assertThrows(
            DomainException.class,
            () ->
                service.create(
                    owner,
                    space.id(),
                    key,
                    new SessionCreateDTO(
                        null, List.of(new TextDTO("Different prompt")), null, null, null)));

    assertEquals(sessionId, first.sessionId());
    assertEquals(threadId, first.threadId());
    assertEquals(commandId, first.commandId());
    assertEquals("1", first.treeVersion());
    assertEquals(first, replay);
    assertEquals("IDEMPOTENCY_KEY_REUSED", reusedKeyError.reason());
    assertEquals(
        1L,
        jdbc.sql("select count(*) from workspace_resource where workspace_id = :space")
            .param("space", space.id())
            .query(Long.class)
            .single());
    verifyNoInteractions(acceptance, settings);
  }

  @Test
  void createRejectsEmptyMessageAfterResolvingRealSpaceAndFolder() {
    var jdbc = jdbc();
    var owner = testUser(jdbc, "session-owner");
    var spaces = new ChatSpaceService(jdbc, new StudioUserLock(jdbc));
    var space =
        spaces.create(
            owner,
            UUID.randomUUID(),
            new ChatSpaceService.Create("Sessions", "Root", null, null, false));
    var acceptance = mock(HarnessCommandAcceptanceOrchestrator.class);
    var service =
        new WorkspaceSessions(
            spaces,
            jdbc,
            new WorkspacePlacements(jdbc),
            mock(HarnessPlacementQuery.class),
            mock(WorkspaceTreeService.class),
            runtimeProvider(),
            acceptance,
            mock(IdleThreadOrchestrator.class),
            mock(SessionDeletionOrchestrator.class),
            mock(SessionInitialSettings.class));

    var error =
        assertThrows(
            DomainException.class,
            () ->
                service.create(
                    owner,
                    space.id(),
                    UUID.randomUUID(),
                    new SessionCreateDTO(null, List.of(), null, null, null)));

    assertEquals("INVALID_ARGUMENT", error.reason());
    verifyNoInteractions(acceptance);
    assertEquals(
        0L,
        jdbc.sql("select count(*) from workspace_resource where workspace_id = :space")
            .param("space", space.id())
            .query(Long.class)
            .single());
  }

  private static JdbcClient jdbc() {
    var dataSource =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    return JdbcClient.create(dataSource);
  }

  private static StudioPrincipal testUser(JdbcClient jdbc, String username) {
    UUID id = UUID.randomUUID();
    jdbc.sql(
            """
            insert into studio_user (id, username, role, password_hash)
            select :id, :username, 'USER', password_hash from studio_user where username = 'kkstudio'
            """)
        .param("id", id)
        .param("username", username)
        .update();
    return new StudioPrincipal(id, username, StudioRole.USER);
  }

  private static ObjectProvider<HarnessRuntime> runtimeProvider() {
    return new StaticListableBeanFactory().getBeanProvider(HarnessRuntime.class);
  }
}
