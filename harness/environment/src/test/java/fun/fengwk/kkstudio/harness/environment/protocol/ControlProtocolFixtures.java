package fun.fengwk.kkstudio.harness.environment.protocol;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Java 与 TypeScript 共享的控制消息 fixture。 */
final class ControlProtocolFixtures {

  static final String ENVIRONMENT_ID = "3f2a9c1e-7b4d-4e2a-9c6f-1a2b3c4d5e6f";
  static final String INVOCATION_ID = "invoke-0001";
  static final String CAPABILITY_ID = "fs.edit";
  static final String CAPABILITY_VERSION = "1.0.0";
  static final String ARGUMENTS_JSON =
      "{\"path\":\"src/main/java/App.java\",\"exponent\":1e-7,"
          + "\"highPrecision\":0.123456789012345678901234567890,\"negativeZero\":-0}";
  static final long TIMEOUT_MILLIS = 30_000L;
  static final String EXPECTED_PATH = "src/main/java/App.java";
  static final String SHA_A = "a".repeat(64);
  static final String CALL_ID = "call-0001";
  static final String DETAILS_JSON =
      "{\"durationMillis\":12,\"ratio\":1e-7,"
          + "\"precise\":0.123456789012345678901234567890,\"negativeZero\":-0}";
  static final String TEXT_CONTENT = "completed";
  static final String JSON_CONTENT =
      "{\"answer\":42,\"exponent\":1e-7,"
          + "\"highPrecision\":0.123456789012345678901234567890,\"negativeZero\":-0}";

  static final String[] GOLDEN_NAMES = {
    "invoke.bin",
    "completed.bin",
    "execution-exited.bin",
    "settings.bin",
    "ready.bin",
    "heartbeat.bin",
    "runtime-status.bin",
    "started.bin",
    "progress.bin",
    "failed.bin",
    "cancel.bin",
    "cancelled.bin",
    "recover.bin",
    "recover-not-found.bin",
    "upload-request.bin",
    "upload-ticket.bin",
    "upload-commit.bin",
    "policy.bin",
    "update.bin",
    "update-result.bin",
    "ide-command.bin",
    "ide-result.bin",
    "ide-lease.bin",
    "error.bin"
  };

  private static final String GOLDEN_DIRECTORY =
      Path.of("src", "main", "protocol", "golden").toString();

  private ControlProtocolFixtures() {}

  static byte[] golden(String name) throws IOException {
    return Files.readAllBytes(Path.of(GOLDEN_DIRECTORY, name));
  }

  static byte[] goldenUnchecked(String name) {
    try {
      return golden(name);
    } catch (IOException error) {
      throw new IllegalStateException(error);
    }
  }

  static Envelope invokeEnvelope() {
    return envelope(
        MessageType.INVOKE,
        Envelope.newBuilder()
            .setInvoke(
                Invoke.newBuilder()
                    .setCapabilityId(CAPABILITY_ID)
                    .setCapabilityVersion(CAPABILITY_VERSION)
                    .setArgumentsJson(ARGUMENTS_JSON)
                    .setTimeoutMillis(TIMEOUT_MILLIS)
                    .setExpectedFile(
                        ExpectedFile.newBuilder().setPath(EXPECTED_PATH).setSha256(SHA_A))));
  }

  static Envelope completedEnvelope() {
    return envelope(
        MessageType.COMPLETED,
        Envelope.newBuilder()
            .setCompleted(
                Completed.newBuilder()
                    .setResult(
                        CapabilityResult.newBuilder()
                            .setCallId(CALL_ID)
                            .setError(false)
                            .setDetailsJson(DETAILS_JSON)
                            .addContents(text(TEXT_CONTENT))
                            .addContents(json(JSON_CONTENT))
                            .addContents(
                                resource(
                                    "7c9e6679-7425-40de-944b-e07fc1f90ae7",
                                    "text/plain",
                                    "notes.txt",
                                    12L,
                                    "b".repeat(64),
                                    "hello world!"))
                            .addContents(
                                resource(
                                    "9b2f4c6a-1d3e-4f5a-8b7c-2d1e0f3a4b5c",
                                    "application/octet-stream",
                                    null,
                                    0L,
                                    "0".repeat(64),
                                    null)))));
  }

  static Envelope completedEnvelopeWithText(String textValue) {
    return envelope(
        MessageType.COMPLETED,
        Envelope.newBuilder()
            .setCompleted(
                Completed.newBuilder()
                    .setResult(
                        CapabilityResult.newBuilder()
                            .setCallId(CALL_ID)
                            .setError(false)
                            .setDetailsJson("{}")
                            .addContents(text(textValue)))));
  }

  static Envelope executionExitedEnvelope() {
    return envelope(
        MessageType.EXECUTION_EXITED,
        Envelope.newBuilder().setExecutionExited(ExecutionExited.getDefaultInstance()));
  }

  static Content text(String value) {
    return Content.newBuilder().setText(TextContent.newBuilder().setText(value)).build();
  }

  static Content json(String value) {
    return Content.newBuilder().setJson(JsonContent.newBuilder().setJson(value)).build();
  }

  static Content resource(
      String uploadId, String mediaType, String name, long size, String sha256, String preview) {
    ResourceContent.Builder builder =
        ResourceContent.newBuilder()
            .setUploadId(uploadId)
            .setMediaType(mediaType)
            .setSize(size)
            .setSha256(sha256);
    if (name != null) {
      builder.setName(name);
    }
    if (preview != null) {
      builder.setPreview(preview);
    }
    return Content.newBuilder().setResource(builder).build();
  }

  static Envelope envelope(MessageType messageType, Envelope.Builder builder) {
    return builder
        .setMessageType(messageType)
        .setEnvironmentId(ENVIRONMENT_ID)
        .setInvocationId(INVOCATION_ID)
        .build();
  }
}
