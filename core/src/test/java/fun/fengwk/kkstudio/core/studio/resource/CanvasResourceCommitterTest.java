package fun.fengwk.kkstudio.core.studio.resource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import fun.fengwk.kkstudio.core.ai.runtime.persistence.postgresql.PostgresSchemaSupport;
import fun.fengwk.kkstudio.core.persistence.test.PostgresSpringTestSupport;
import fun.fengwk.kkstudio.studio.canvas.CanvasCommandService;
import fun.fengwk.kkstudio.studio.canvas.CanvasDocument;
import fun.fengwk.kkstudio.studio.canvas.CanvasResource;
import fun.fengwk.kkstudio.studio.canvas.CanvasResourceKind;
import fun.fengwk.kkstudio.studio.canvas.CanvasResourceRepository;
import fun.fengwk.kkstudio.studio.canvas.CanvasUpload;
import fun.fengwk.kkstudio.studio.canvas.CanvasUploadRepository;

import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

class CanvasResourceCommitterTest extends PostgresSpringTestSupport {

  @Autowired private CanvasCommandService commandService;
  @Autowired private CanvasResourceRepository resourceRepository;
  @Autowired private CanvasUploadRepository uploadRepository;
  @Autowired private CanvasResourceCommitter committer;

  /** PostgreSQL FOR UPDATE + insert-if-absent 让并发 finalize 只留下一个 Resource。 */
  @Test
  void concurrentUploadCommitConvergesToOneResource() throws Exception {
    CanvasDocument canvas = commandService.createCanvas("commit");
    long id = PostgresSchemaSupport.FIXTURE_IDS.incrementAndGet();
    Instant now = Instant.parse("2026-08-10T00:00:00Z");
    CanvasUpload upload =
        new CanvasUpload(
            id,
            canvas.id(),
            CanvasResourceKind.IMAGE,
            "a.png",
            "image/png",
            3L,
            now.plusSeconds(60),
            now);
    CanvasResource candidate =
        new CanvasResource(
            id,
            canvas.id(),
            CanvasResourceKind.IMAGE,
            "image/png",
            "a.png",
            3L,
            null,
            "{\"width\":16,\"height\":12}",
            now);
    uploadRepository.add(upload);
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      Future<CanvasResource> first = executor.submit(() -> commitAfter(start, upload, candidate));
      Future<CanvasResource> second = executor.submit(() -> commitAfter(start, upload, candidate));
      start.countDown();

      CanvasResource firstResult = first.get();
      CanvasResource secondResult = second.get();
      assertEquals(firstResult, secondResult);
      assertEquals(id, firstResult.id());
      assertEquals(firstResult, resourceRepository.findById(canvas.id(), id).orElseThrow());
      assertTrue(uploadRepository.findById(canvas.id(), id).isEmpty());
      assertEquals(1L, countResource(canvas.id(), id));
    } finally {
      executor.shutdownNow();
    }
  }

  /** Provider materialize 重复提交同一预分配 id 返回既有不可变行。 */
  @Test
  void materializedCommitIsIdempotent() {
    CanvasDocument canvas = commandService.createCanvas("materialize");
    long id = PostgresSchemaSupport.FIXTURE_IDS.incrementAndGet();
    CanvasResource candidate =
        new CanvasResource(
            id,
            canvas.id(),
            CanvasResourceKind.AUDIO,
            "audio/mpeg",
            "a.mp3",
            3L,
            null,
            "{\"durationMs\":250}",
            Instant.parse("2026-08-10T00:00:00Z"));

    CanvasResource first = committer.commitMaterialized(candidate);
    CanvasResource replay = committer.commitMaterialized(candidate);
    assertEquals(first, replay);
    assertEquals(candidate.id(), first.id());
  }

  private CanvasResource commitAfter(
      CountDownLatch start, CanvasUpload upload, CanvasResource candidate)
      throws InterruptedException {
    start.await();
    return committer.commitUpload(upload, candidate);
  }

  private long countResource(long canvasId, long resourceId) throws Exception {
    try (var connection = PostgresSchemaSupport.newConnection();
        var statement =
            connection.prepareStatement(
                "select count(*) from canvas_resource where canvas_id = ? and id = ?")) {
      statement.setLong(1, canvasId);
      statement.setLong(2, resourceId);
      try (var resultSet = statement.executeQuery()) {
        resultSet.next();
        return resultSet.getLong(1);
      }
    }
  }
}
