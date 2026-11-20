package fun.fengwk.kkstudio.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.platform.daemon.server.DaemonResourceTicketService.Ticket;
import fun.fengwk.kkstudio.platform.daemon.server.DaemonResourceTicketService.TransferRequest;
import fun.fengwk.kkstudio.platform.environment.server.StorageDaemonResourceTicketService;
import fun.fengwk.kkstudio.platform.settings.SystemSettingsSnapshot;
import fun.fengwk.kkstudio.platform.storage.StorageMaintenance;
import fun.fengwk.kkstudio.platform.storage.StorageObjectKeys;

import javax.imageio.ImageIO;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Exercises the daemon resource ticket adapter against PostgreSQL and an isolated real MinIO. */
class StorageDaemonResourceTicketIntegrationTest extends WebPostgresTestSupport {

  private static final StartupMinioFixture MINIO = StartupMinioFixture.start();
  private static final int WIDTH = 7;
  private static final int HEIGHT = 5;
  private static final String INVOCATION_ID = UUID.randomUUID().toString();
  private static final EnvironmentId ENVIRONMENT_ID = EnvironmentId.of(UUID.randomUUID());

  @Autowired private StorageDaemonResourceTicketService tickets;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private SystemSettingsSnapshot settings;

  @TestBean(name = "testS3Client", methodName = "s3Client")
  private S3Client fixtureClient;

  @MockitoBean private StorageMaintenance maintenance;

  @DynamicPropertySource
  static void configureIsolatedMinio(DynamicPropertyRegistry registry) {
    registry.add("kk-studio.storage.s3.endpoint", MINIO::endpoint);
    registry.add("kk-studio.storage.s3.public-endpoint", MINIO::endpoint);
    registry.add("kk-studio.storage.s3.region", MINIO::region);
    registry.add("kk-studio.storage.s3.bucket", MINIO::bucket);
    registry.add("kk-studio.storage.s3.access-key", MINIO::accessKey);
    registry.add("kk-studio.storage.s3.secret-key", MINIO::secretKey);
  }

  @AfterAll
  static void stopIsolatedMinio() {
    MINIO.close();
  }

  /** Real image bytes must be probed at commit rather than trusting Daemon-supplied dimensions. */
  @Test
  void reservesAndCommitsPngAndJpegWithServerProbedDimensions() throws Exception {
    for (ImageFixture fixture : List.of(image("png", "image/png"), image("jpeg", "image/jpeg"))) {
      Ticket.Pending pending = reservePending(fixture);
      assertEquals(200, signedPut(pending, fixture.bytes()));
      assertEquals(412, signedPut(pending, fixture.bytes()));

      Ticket.Ready ready =
          assertInstanceOf(
              Ticket.Ready.class,
              tickets.commit(ENVIRONMENT_ID, INVOCATION_ID, pending.uploadId()));

      assertEquals(pending.uploadId(), ready.uploadId());
      BlobFacts facts = blobFacts(pending.uploadId());
      assertEquals(fixture.mediaType(), facts.mediaType());
      assertEquals(fixture.bytes().length, facts.size());
      assertEquals(sha256Hex(fixture.bytes()), facts.sha256());
      assertEquals(WIDTH, facts.width());
      assertEquals(HEIGHT, facts.height());
    }
  }

  /**
   * A renewal preserves the id, key, headers and reservation deadline, even when the remaining TTL
   * is shorter.
   */
  @Test
  void renewsPendingTicketWithoutChangingUploadBindingOrLifetime() throws Exception {
    ImageFixture fixture = image("png", "image/png");
    Ticket.Pending initial = reservePending(fixture);
    jdbc.update(
        "update storage_upload set created_at = current_timestamp - (? * interval '1 second') where id = ?",
        settings.get().storageMedia().uploadExpiresSeconds() - 30L,
        initial.uploadId());
    long rowsBefore = uploadCount();
    String createdAtBefore = createdAt(initial.uploadId());

    Ticket.Pending renewed =
        assertInstanceOf(
            Ticket.Pending.class, tickets.renew(ENVIRONMENT_ID, INVOCATION_ID, initial.uploadId()));

    assertEquals(initial.uploadId(), renewed.uploadId());
    assertEquals("PUT", renewed.presignedPut().method());
    assertEquals(initial.presignedPut().headers(), renewed.presignedPut().headers());
    String initialPath = URI.create(initial.presignedPut().url()).getRawPath();
    assertTrue(
        initialPath.endsWith("/" + StorageObjectKeys.uploadOriginal(initial.uploadId())),
        "the signed path must stay bound to this upload key");
    assertEquals(initialPath, URI.create(renewed.presignedPut().url()).getRawPath());
    String initialTtl = queryParameter(initial.presignedPut().url(), "X-Amz-Expires");
    assertNotNull(initialTtl, "presigned PUT must carry a bounded signature TTL");
    long renewedTtl = Long.parseLong(queryParameter(renewed.presignedPut().url(), "X-Amz-Expires"));
    assertTrue(renewedTtl > 0 && renewedTtl <= 30 && renewedTtl < Long.parseLong(initialTtl));
    assertEquals(createdAtBefore, createdAt(initial.uploadId()));
    assertEquals(200, signedPut(renewed, fixture.bytes()));
    assertEquals(rowsBefore, uploadCount(), "renewal must not create another reservation");
    assertEquals(
        new Ticket.Ready(initial.uploadId()),
        tickets.commit(ENVIRONMENT_ID, INVOCATION_ID, initial.uploadId()));
    assertEquals(
        new Ticket.Ready(initial.uploadId()),
        tickets.renew(ENVIRONMENT_ID, INVOCATION_ID, initial.uploadId()));
    tickets.release(ENVIRONMENT_ID, INVOCATION_ID, initial.uploadId());
    assertInstanceOf(
        Ticket.Failed.class, tickets.renew(ENVIRONMENT_ID, INVOCATION_ID, initial.uploadId()));
  }

  /**
   * An expired PENDING reservation cannot be renewed or silently replaced by another upload row.
   */
  @Test
  void refusesRenewalAfterReservationExpiry() throws Exception {
    Ticket.Pending initial = reservePending(image("png", "image/png"));
    jdbc.update(
        "update storage_upload set created_at = current_timestamp - interval '2 days' where id = ?",
        initial.uploadId());
    long rowsBefore = uploadCount();

    Ticket result = tickets.renew(ENVIRONMENT_ID, INVOCATION_ID, initial.uploadId());

    assertInstanceOf(Ticket.Failed.class, result);
    assertEquals(rowsBefore, uploadCount(), "refused renewal must not reserve a replacement row");
  }

  @Test
  void commitRejectsDigestSizeAndMimeMismatchesWithoutBindingBlobs() throws Exception {
    ImageFixture png = image("png", "image/png");

    Ticket.Pending wrongDigest = reservePending(png);
    byte[] differentBytes = png.bytes().clone();
    differentBytes[differentBytes.length - 1] ^= 1;
    putUpload(wrongDigest.uploadId(), differentBytes, png.mediaType());
    assertCommitVerificationFailure(wrongDigest.uploadId());

    ImageFixture wrongSizeFixture =
        new ImageFixture(png.mediaType(), png.bytes(), png.bytes().length + 1);
    Ticket.Pending wrongSize = reservePending(wrongSizeFixture);
    assertEquals(200, signedPut(wrongSize, png.bytes()));
    assertCommitVerificationFailure(wrongSize.uploadId());

    ImageFixture jpeg = image("jpeg", "image/jpeg");
    ImageFixture wrongMimeDeclaration =
        new ImageFixture(png.mediaType(), jpeg.bytes(), jpeg.bytes().length);
    Ticket.Pending wrongMime = reservePending(wrongMimeDeclaration);
    // The signed Content-Type still says PNG; only probing the bytes reveals the JPEG.
    assertEquals(200, signedPut(wrongMime, jpeg.bytes()));
    assertCommitVerificationFailure(wrongMime.uploadId());
  }

  private Ticket.Pending reservePending(ImageFixture fixture) {
    Ticket result =
        tickets.reserve(
            ENVIRONMENT_ID,
            INVOCATION_ID,
            new TransferRequest(
                UUID.randomUUID(),
                fixture.mediaType(),
                "daemon-image." + extension(fixture.mediaType()),
                fixture.declaredSize(),
                sha256Hex(fixture.bytes())));
    return assertInstanceOf(Ticket.Pending.class, result);
  }

  private void putUpload(UUID uploadId, byte[] bytes, String mediaType) {
    fixtureClient.putObject(
        PutObjectRequest.builder()
            .bucket(MINIO.bucket())
            .key(StorageObjectKeys.uploadOriginal(uploadId))
            .contentType(mediaType)
            .checksumSHA256(
                Base64.getEncoder().encodeToString(HexFormat.of().parseHex(sha256Hex(bytes))))
            .build(),
        RequestBody.fromBytes(bytes));
  }

  private static int signedPut(Ticket.Pending pending, byte[] bytes) throws Exception {
    var put = pending.presignedPut();
    var request =
        HttpRequest.newBuilder(URI.create(put.url()))
            .timeout(Duration.ofSeconds(10))
            .PUT(HttpRequest.BodyPublishers.ofByteArray(bytes));
    put.headers().forEach(request::header);
    try (HttpClient client = HttpClient.newHttpClient()) {
      return client.send(request.build(), HttpResponse.BodyHandlers.discarding()).statusCode();
    }
  }

  private static S3Client s3Client() {
    return S3Client.builder()
        .endpointOverride(URI.create(MINIO.endpoint()))
        .region(Region.of(MINIO.region()))
        .credentialsProvider(
            StaticCredentialsProvider.create(
                AwsBasicCredentials.create(MINIO.accessKey(), MINIO.secretKey())))
        .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
        .build();
  }

  private void assertCommitVerificationFailure(UUID uploadId) {
    Ticket.Failed failed =
        assertInstanceOf(
            Ticket.Failed.class, tickets.commit(ENVIRONMENT_ID, INVOCATION_ID, uploadId));
    assertFalse(failed.message().isBlank());
    assertEquals(
        true,
        jdbc.queryForObject(
            "select blob_id is null from storage_upload where id = ?", Boolean.class, uploadId),
        "verification failure must preserve the pending reservation");
    assertEquals(
        0,
        jdbc.queryForObject("select count(*) from storage_blob", Integer.class),
        "a rejected object must not be bound to a blob");
  }

  private BlobFacts blobFacts(UUID uploadId) {
    return jdbc.queryForObject(
        "select b.media_type, b.size_bytes, b.sha256, b.width, b.height"
            + " from storage_upload u join storage_blob b on b.id = u.blob_id where u.id = ?",
        (rs, rowNum) ->
            new BlobFacts(
                rs.getString("media_type"),
                rs.getLong("size_bytes"),
                rs.getString("sha256"),
                rs.getInt("width"),
                rs.getInt("height")),
        uploadId);
  }

  private long uploadCount() {
    return jdbc.queryForObject("select count(*) from storage_upload", Long.class);
  }

  private String createdAt(UUID uploadId) {
    return jdbc.queryForObject(
        "select created_at::text from storage_upload where id = ?", String.class, uploadId);
  }

  private static String queryParameter(String url, String key) {
    Map<String, String> parameters = new HashMap<>();
    for (String part : URI.create(url).getRawQuery().split("&")) {
      String[] pair = part.split("=", 2);
      parameters.put(pair[0], pair.length == 2 ? pair[1] : "");
    }
    return parameters.get(key);
  }

  private static String extension(String mediaType) {
    return mediaType.substring("image/".length());
  }

  private static ImageFixture image(String format, String mediaType) throws IOException {
    BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
    image.setRGB(0, 0, 0x336699);
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    assertTrue(ImageIO.write(image, format, output), "image writer must be available");
    byte[] bytes = output.toByteArray();
    return new ImageFixture(mediaType, bytes, bytes.length);
  }

  private static String sha256Hex(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException error) {
      throw new AssertionError(error);
    }
  }

  private record ImageFixture(String mediaType, byte[] bytes, long declaredSize) {}

  private record BlobFacts(String mediaType, long size, String sha256, int width, int height) {}
}
