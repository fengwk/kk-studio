package fun.fengwk.kkstudio.workspace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PSQLException;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import fun.fengwk.kkstudio.common.error.DomainErrorCode;
import fun.fengwk.kkstudio.common.error.DomainException;
import fun.fengwk.kkstudio.platform.account.StudioPrincipal;
import fun.fengwk.kkstudio.platform.account.StudioRole;
import fun.fengwk.kkstudio.platform.account.StudioUserLock;
import fun.fengwk.kkstudio.platform.orchestration.ResourceOwnership;

import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@Testcontainers
class WorkspacePostgresIntegrationTest {
  @Container
  private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

  @BeforeAll
  static void migrate() {
    Flyway.configure()
        .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
        .load()
        .migrate();
  }

  /** Both transactions try to archive different space rows; exactly one may succeed. */
  @Test
  void concurrentArchivePreservesAnActiveSpace() throws Exception {
    try (var context = serviceContext();
        var executor = Executors.newFixedThreadPool(2)) {
      var service = context.getBean(ChatSpaceService.class);
      var owner = testUser(context, "archive-owner");
      var first = create(service, owner, "First");
      var second = create(service, owner, "Second");
      var barrier = new CyclicBarrier(2);
      var a = executor.submit(() -> archive(service, owner, first.id(), barrier));
      var b = executor.submit(() -> archive(service, owner, second.id(), barrier));
      assertEquals(
          Set.of("ARCHIVED", "LAST_ACTIVE_CHAT_SPACE"),
          Set.of(a.get(10, TimeUnit.SECONDS), b.get(10, TimeUnit.SECONDS)));
      assertEquals(
          1, service.list(owner).stream().filter(space -> space.archivedAt() == null).count());
    }
  }

  /**
   * Mutating a space changes only the config version and cannot alter the stored creation result.
   */
  @Test
  void patchChecksOwnerAndVersionAndCreateReplaysOriginal() {
    try (var context = serviceContext()) {
      var service = context.getBean(ChatSpaceService.class);
      var owner = testUser(context, "replay-owner");
      var outsider = testUser(context, "replay-outsider");
      var key = UUID.randomUUID();
      var request = new ChatSpaceService.Create("Original", "Root", null, null, false);
      var space = service.create(owner, key, request);
      var rename = new ChatSpaceService.Patch(0, "Renamed", null, null, null, null);
      assertEquals(
          DomainErrorCode.NOT_FOUND,
          assertThrows(DomainException.class, () -> service.patch(outsider, space.id(), rename))
              .code());
      assertEquals(0, service.list(outsider).size());
      var renamed = service.patch(owner, space.id(), rename);
      assertEquals(1, renamed.version());
      assertEquals(space.treeVersion(), renamed.treeVersion());
      assertEquals(
          DomainErrorCode.VERSION_CONFLICT,
          assertThrows(DomainException.class, () -> service.patch(owner, space.id(), rename))
              .code());
      assertEquals(space, service.create(owner, key, request));
      assertEquals(
          "IDEMPOTENCY_KEY_REUSED",
          assertThrows(
                  DomainException.class,
                  () ->
                      service.create(
                          owner,
                          key,
                          new ChatSpaceService.Create("Different", "Root", null, null, false)))
              .reason());
      assertEquals("Renamed", service.list(owner).getFirst().title());
    }
  }

  /** Cycle and stale moves leave both sequences unchanged; a valid move compacts both sides. */
  @Test
  void folderMoveChecksTopologyAndVersionBeforeReordering() {
    try (var context = serviceContext()) {
      var spaces = context.getBean(ChatSpaceService.class);
      var folders = context.getBean(FolderService.class);
      var owner = testUser(context, "folder-owner");
      var space = create(spaces, owner, "Folders").id();
      var jdbc = context.getBean(JdbcClient.class);
      var root =
          jdbc.sql("select id from workspace_folder where workspace_id = :space")
              .param("space", space)
              .query(UUID.class)
              .single();
      var key = UUID.randomUUID();
      var createChild = new FolderService.Create(root, "Child", 0);
      var child = folders.create(owner, space, key, createChild);
      var sibling =
          folders.create(
              owner, space, UUID.randomUUID(), new FolderService.Create(root, "Sibling", 1));
      var otherRoot =
          folders.create(
              owner, space, UUID.randomUUID(), new FolderService.Create(null, "Other", 2));
      assertEquals(3, folders.rename(owner, space, child.id(), "Renamed"));
      assertEquals(child, folders.create(owner, space, key, createChild));
      assertEquals(
          "IDEMPOTENCY_KEY_REUSED",
          assertThrows(
                  DomainException.class,
                  () ->
                      folders.create(
                          owner, space, key, new FolderService.Create(root, "Different", 0)))
              .reason());
      assertEquals(
          "INVALID_FOLDER",
          assertThrows(
                  DomainException.class,
                  () -> folders.move(owner, space, root, child.id(), null, 3))
              .reason());
      assertEquals(
          DomainErrorCode.VERSION_CONFLICT,
          assertThrows(
                  DomainException.class,
                  () -> folders.move(owner, space, child.id(), otherRoot.id(), null, 2))
              .code());
      assertEquals(4, folders.move(owner, space, child.id(), otherRoot.id(), null, 3));
      assertEquals(
          0,
          jdbc.sql("select position from workspace_folder where id = :id")
              .param("id", sibling.id())
              .query(Integer.class)
              .single());
      assertEquals(
          otherRoot.id(),
          jdbc.sql("select parent_id from workspace_folder where id = :id")
              .param("id", child.id())
              .query(UUID.class)
              .single());
      assertEquals(
          "FOLDER_NOT_EMPTY",
          assertThrows(DomainException.class, () -> folders.delete(owner, space, root, 4))
              .reason());
      assertEquals(5, folders.delete(owner, space, sibling.id(), 4));
      assertEquals(6, folders.delete(owner, space, root, 5));
      assertEquals(7, folders.delete(owner, space, child.id(), 6));
      assertEquals(
          "LAST_ROOT_FOLDER",
          assertThrows(DomainException.class, () -> folders.delete(owner, space, otherRoot.id(), 7))
              .reason());
    }
  }

  /** The batch port resolves the proper owner for each resource, including archived spaces. */
  @Test
  void batchOwnershipPreservesSpaceAndResourceKind() {
    try (var context = serviceContext()) {
      var spaces = context.getBean(ChatSpaceService.class);
      var ownership = context.getBean(ResourceOwnership.class);
      var jdbc = context.getBean(JdbcClient.class);
      var first = testUser(context, "first-canvas-owner");
      var second = testUser(context, "second-canvas-owner");
      var archivedSpace = create(spaces, first, "Archived");
      create(spaces, first, "Active");
      spaces.patch(
          first, archivedSpace.id(), new ChatSpaceService.Patch(0, null, true, null, null, null));
      var otherSpace = create(spaces, second, "Other");
      UUID a = placeCanvas(jdbc, archivedSpace.id());
      UUID b = placeCanvas(jdbc, otherSpace.id());
      assertEquals(
          Map.of(
              a,
              new ResourceOwnership.Owner(archivedSpace.id(), "Archived", first.userId()),
              b,
              new ResourceOwnership.Owner(otherSpace.id(), "Other", second.userId())),
          ownership.findOwners(ResourceOwnership.Kind.CANVAS, List.of(a, b, a, UUID.randomUUID())));
      assertEquals(Map.of(), ownership.findOwners(ResourceOwnership.Kind.SESSION, List.of(a, b)));
    }
  }

  /** Placement writes keep ordered edges and enforce resource-kind and replay boundaries. */
  @Test
  void placementsMoveAndDeleteOrderedResources() {
    try (var context = serviceContext()) {
      var spaces = context.getBean(ChatSpaceService.class);
      var folders = context.getBean(FolderService.class);
      var placements = context.getBean(WorkspacePlacements.class);
      var owner = testUser(context, "placement-owner");
      var space = create(spaces, owner, "Placement").id();
      var jdbc = context.getBean(JdbcClient.class);
      UUID root =
          jdbc.sql("select id from workspace_folder where workspace_id = :space")
              .param("space", space)
              .query(UUID.class)
              .single();
      var destination =
          folders.create(
              owner, space, UUID.randomUUID(), new FolderService.Create(null, "Destination", 0));
      UUID first = UUID.randomUUID();
      UUID second = UUID.randomUUID();
      jdbc.sql("insert into canvas_document (id, title) values (:id, 'First')")
          .param("id", first)
          .update();
      jdbc.sql("insert into canvas_document (id, title) values (:id, 'Second')")
          .param("id", second)
          .update();
      String firstHash = WorkspacePlacements.fingerprint("first");
      placements.insert(
          space, root, first, ResourceOwnership.Kind.CANVAS, null, firstHash, 1, null);
      placements.insert(
          space,
          root,
          second,
          ResourceOwnership.Kind.CANVAS,
          null,
          WorkspacePlacements.fingerprint("second"),
          2,
          null);

      assertEquals(
          List.of(second, first),
          placements.list(space).stream().map(WorkspacePlacements.Edge::id).toList());
      assertEquals(first, placements.require(space, first, ResourceOwnership.Kind.CANVAS).id());
      assertEquals(
          "RESOURCE_NOT_FOUND",
          assertThrows(
                  DomainException.class,
                  () -> placements.require(space, first, ResourceOwnership.Kind.SESSION))
              .reason());
      assertEquals(first, placements.replay(space, first, firstHash).orElseThrow().id());
      assertEquals(
          "IDEMPOTENCY_KEY_REUSED",
          assertThrows(DomainException.class, () -> placements.replay(space, first, "different"))
              .reason());

      placements.move(
          space,
          placements.require(space, first, ResourceOwnership.Kind.CANVAS),
          destination.id(),
          null);
      assertEquals(destination.id(), placements.find(space, first).orElseThrow().folderId());
      placements.delete(space, placements.require(space, second, ResourceOwnership.Kind.CANVAS));
      assertEquals(
          List.of(first),
          placements.list(space).stream().map(WorkspacePlacements.Edge::id).toList());
      assertThrows(DomainException.class, () -> placements.requireFolder(space, UUID.randomUUID()));
    }
  }

  /** Space updates preserve owner scope, normalize names, and protect the final active space. */
  @Test
  void spacePatchChecksOwnershipAndKeepsAnActiveSpace() {
    try (var context = serviceContext()) {
      var service = context.getBean(ChatSpaceService.class);
      var owner = testUser(context, "space-patch-owner");
      var outsider = testUser(context, "space-patch-outsider");
      var first = create(service, owner, "First");
      var second = create(service, owner, "Second");

      var renamed =
          service.patch(
              owner,
              first.id(),
              new ChatSpaceService.Patch(0, "  Renamed  ", null, null, null, true));
      assertEquals("Renamed", renamed.title());
      assertEquals(1, renamed.version());
      assertEquals(true, renamed.defaultYoloEnabled());
      assertEquals(false, service.allows(outsider, first.id()));
      assertEquals(
          DomainErrorCode.NOT_FOUND,
          assertThrows(
                  DomainException.class,
                  () ->
                      service.patch(
                          outsider,
                          first.id(),
                          new ChatSpaceService.Patch(1, null, null, null, null, null)))
              .code());

      var archived =
          service.patch(
              owner, first.id(), new ChatSpaceService.Patch(1, null, true, null, null, null));
      assertEquals(2, archived.version());
      assertEquals(first.id(), service.list(owner).get(1).id());
      assertEquals(
          "LAST_ACTIVE_CHAT_SPACE",
          assertThrows(
                  DomainException.class,
                  () ->
                      service.patch(
                          owner,
                          second.id(),
                          new ChatSpaceService.Patch(0, null, true, null, null, null)))
              .reason());
      var restored =
          service.patch(
              owner, first.id(), new ChatSpaceService.Patch(2, null, false, null, null, null));
      assertEquals(null, restored.archivedAt());
      assertEquals(3, restored.version());
    }
  }

  private static UUID placeCanvas(JdbcClient jdbc, UUID space) {
    UUID canvas = UUID.randomUUID();
    jdbc.sql("insert into canvas_document (id, title) values (:id, 'Canvas')")
        .param("id", canvas)
        .update();
    jdbc.sql(
            """
        insert into workspace_resource (id, workspace_id, folder_id, position, canvas_id)
        select :canvas, workspace_id, id, 0, :canvas from workspace_folder where workspace_id = :space
        """)
        .param("canvas", canvas)
        .param("space", space)
        .update();
    return canvas;
  }

  private static String archive(
      ChatSpaceService service, StudioPrincipal owner, UUID id, CyclicBarrier barrier)
      throws Exception {
    barrier.await(5, TimeUnit.SECONDS);
    try {
      service.patch(owner, id, new ChatSpaceService.Patch(0, null, true, null, null, null));
      return "ARCHIVED";
    } catch (DomainException ex) {
      return ex.reason();
    }
  }

  private static ChatSpaceService.Space create(
      ChatSpaceService service, StudioPrincipal owner, String title) {
    return service.create(
        owner, UUID.randomUUID(), new ChatSpaceService.Create(title, "Root", null, null, false));
  }

  private static StudioPrincipal testUser(
      AnnotationConfigApplicationContext context, String username) {
    UUID id = UUID.randomUUID();
    context
        .getBean(JdbcClient.class)
        .sql(
            """
        insert into studio_user (id, username, role, password_hash)
        values (:id, :username, 'USER', '{noop}workspace-test-password')
        """)
        .param("id", id)
        .param("username", username)
        .update();
    return new StudioPrincipal(id, username, StudioRole.USER);
  }

  private static AnnotationConfigApplicationContext serviceContext() {
    var dataSource =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    var context = new AnnotationConfigApplicationContext();
    context.register(
        TransactionConfig.class,
        ChatSpaceService.class,
        FolderService.class,
        WorkspaceResourceOwnership.class,
        WorkspacePlacements.class,
        StudioUserLock.class);
    context.registerBean(JdbcClient.class, () -> JdbcClient.create(dataSource));
    context.registerBean(
        JdbcTransactionManager.class, () -> new JdbcTransactionManager(dataSource));
    context.refresh();
    return context;
  }

  @EnableTransactionManagement
  static class TransactionConfig {}

  /** Root directories share the same uniqueness boundary despite a null parent. */
  @Test
  void nullParentStillEnforcesSiblingName() throws SQLException {
    try (var connection =
            DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        var sql = connection.createStatement()) {
      connection.setAutoCommit(false);
      sql.executeUpdate(
          """
          insert into studio_user (id, username, role, password_hash)
          values ('00000000-0000-0000-0000-000000000013', 'folder-constraint-owner', 'USER', '{noop}workspace-test-password')
          """);
      sql.executeUpdate(
          """
          insert into chat_space (id, owner_user_id, title, title_key, create_request_hash)
          values ('00000000-0000-0000-0000-000000000010', '00000000-0000-0000-0000-000000000013', 'Test A', 'test a', repeat('0', 64))
          """);
      sql.executeUpdate(
          """
          insert into workspace_folder (id, workspace_id, name, name_key, position)
          values ('00000000-0000-0000-0000-000000000011', '00000000-0000-0000-0000-000000000010', 'Root', 'root', 0)
          """);
      var error =
          assertThrows(
              PSQLException.class,
              () ->
                  sql.executeUpdate(
                      """
          insert into workspace_folder (id, workspace_id, name, name_key, position)
          values ('00000000-0000-0000-0000-000000000012', '00000000-0000-0000-0000-000000000010', 'ROOT', 'root', 1)
          """));
      assertEquals("uk_workspace_folder_name", error.getServerErrorMessage().getConstraint());
      connection.rollback();
    }
  }

  /** Swaps may temporarily collide, but a duplicate position cannot commit. */
  @Test
  void positionUniquenessIsDeferredUntilCommit() throws SQLException {
    try (var connection =
            DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        var sql = connection.createStatement()) {
      connection.setAutoCommit(false);
      sql.executeUpdate(
          """
          insert into studio_user (id, username, role, password_hash)
          values ('00000000-0000-0000-0000-000000000023', 'position-constraint-owner', 'USER', '{noop}workspace-test-password')
          """);
      sql.executeUpdate(
          """
          insert into chat_space (id, owner_user_id, title, title_key, create_request_hash)
          values ('00000000-0000-0000-0000-000000000020', '00000000-0000-0000-0000-000000000023', 'Test B', 'test b', repeat('0', 64))
          """);
      assertEquals(
          2,
          sql.executeUpdate(
              """
          insert into workspace_folder (id, workspace_id, name, name_key, position) values
              ('00000000-0000-0000-0000-000000000021', '00000000-0000-0000-0000-000000000020', 'A', 'a', 0),
              ('00000000-0000-0000-0000-000000000022', '00000000-0000-0000-0000-000000000020', 'B', 'b', 0)
          """));
      var error = assertThrows(PSQLException.class, connection::commit);
      assertEquals("uk_workspace_folder_position", error.getServerErrorMessage().getConstraint());
      connection.rollback();
    }
  }
}
