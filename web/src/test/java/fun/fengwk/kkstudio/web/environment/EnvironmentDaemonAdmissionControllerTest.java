package fun.fengwk.kkstudio.web.environment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.eclipse.jetty.io.ArrayByteBufferPool;
import org.eclipse.jetty.util.thread.ScheduledExecutorScheduler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.platform.daemon.DaemonArchitecture;
import fun.fengwk.kkstudio.platform.daemon.DaemonOperatingSystem;
import fun.fengwk.kkstudio.platform.daemon.protocol.ControlProtocolCodec;
import fun.fengwk.kkstudio.platform.daemon.protocol.UpgradeRequired;
import fun.fengwk.kkstudio.platform.daemon.server.DaemonAdmission;
import fun.fengwk.kkstudio.platform.daemon.server.DaemonControlChannel;
import fun.fengwk.kkstudio.platform.daemon.server.DaemonHandshake;
import fun.fengwk.kkstudio.platform.daemon.server.DaemonSession;
import fun.fengwk.kkstudio.platform.daemon.server.DaemonSessions;
import fun.fengwk.kkstudio.platform.daemon.tunnel.EnvironmentTunnel;
import fun.fengwk.kkstudio.platform.environment.update.DaemonReleaseProvider;
import fun.fengwk.kkstudio.platform.environment.update.DaemonReleaseTarget;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

class EnvironmentDaemonAdmissionControllerTest {

  private static final String SECRET_TOKEN = "secret-super-sensitive-token-12345678";
  private static final String INSTANCE_ID = UUID.randomUUID().toString();
  private static final EnvironmentId ENV_ID = EnvironmentId.of(UUID.randomUUID());

  private TestSessions testSessions;
  private TestReleaseProvider testReleaseProvider;
  private ScheduledExecutorScheduler scheduler;
  private EnvironmentDaemonAdmissionController controller;
  private ListAppender<ILoggingEvent> logAppender;
  private Logger controllerLogger;

  @BeforeEach
  void setUp() {
    testSessions = new TestSessions();
    testReleaseProvider = new TestReleaseProvider();
    scheduler = new ScheduledExecutorScheduler("test-scheduler-", false);

    controller =
        new EnvironmentDaemonAdmissionController(
            testSessions,
            new SingleObjectProvider<>(testReleaseProvider),
            scheduler,
            Runnable::run,
            new ArrayByteBufferPool());

    controllerLogger = (Logger) LoggerFactory.getLogger(EnvironmentDaemonAdmissionController.class);
    logAppender = new ListAppender<>();
    logAppender.start();
    controllerLogger.addAppender(logAppender);
  }

  @AfterEach
  void tearDown() {
    if (controllerLogger != null && logAppender != null) {
      controllerLogger.detachAppender(logAppender);
    }
  }

  @Test
  void missingUpgradeHeadersReturns400() throws Exception {
    MockHttpServletRequest request = newRequest();
    request.removeHeader("Upgrade");
    request.removeHeader("Connection");
    MockHttpServletResponse response = new MockHttpServletResponse();

    controller.handle(request, response);

    assertEquals(400, response.getStatus());
    assertNoSecretLeak(response);
  }

  @Test
  void missingInstanceIdHeaderReturns400() throws Exception {
    MockHttpServletRequest request = newRequest();
    request.removeHeader("KK-Daemon-Instance");
    MockHttpServletResponse response = new MockHttpServletResponse();

    controller.handle(request, response);

    assertEquals(400, response.getStatus());
    assertNoSecretLeak(response);
  }

  @Test
  void missingProtocolVersionHeaderReturns400() throws Exception {
    MockHttpServletRequest request = newRequest();
    request.removeHeader("KK-Daemon-Protocol");
    MockHttpServletResponse response = new MockHttpServletResponse();

    controller.handle(request, response);

    assertEquals(400, response.getStatus());
    assertNoSecretLeak(response);
  }

  @Test
  void malformedProtocolVersionHeaderReturns400() throws Exception {
    MockHttpServletRequest request = newRequest();
    request.removeHeader("KK-Daemon-Protocol");
    request.addHeader("KK-Daemon-Protocol", "not-a-number");
    MockHttpServletResponse response = new MockHttpServletResponse();

    controller.handle(request, response);

    assertEquals(400, response.getStatus());
    assertNoSecretLeak(response);
  }

  @Test
  void missingAuthorizationHeaderReturns401() throws Exception {
    MockHttpServletRequest request = newRequest();
    request.removeHeader("Authorization");
    MockHttpServletResponse response = new MockHttpServletResponse();

    controller.handle(request, response);

    assertEquals(401, response.getStatus());
    assertNoSecretLeak(response);
  }

  @Test
  void malformedAuthorizationHeaderReturns401() throws Exception {
    MockHttpServletRequest request = newRequest();
    request.removeHeader("Authorization");
    request.addHeader("Authorization", "Basic " + SECRET_TOKEN);
    MockHttpServletResponse response = new MockHttpServletResponse();

    controller.handle(request, response);

    assertEquals(401, response.getStatus());
    assertNoSecretLeak(response);
  }

  @Test
  void blankBearerTokenReturns401() throws Exception {
    MockHttpServletRequest request = newRequest();
    request.removeHeader("Authorization");
    request.addHeader("Authorization", "Bearer    ");
    MockHttpServletResponse response = new MockHttpServletResponse();

    controller.handle(request, response);

    assertEquals(401, response.getStatus());
    assertNoSecretLeak(response);
  }

  @Test
  void admissionRejectedReturns401() throws Exception {
    testSessions.admission = new DaemonAdmission.Rejected();
    MockHttpServletRequest request = newRequest();
    MockHttpServletResponse response = new MockHttpServletResponse();

    controller.handle(request, response);

    assertEquals(401, response.getStatus());
    assertNoSecretLeak(response);
  }

  @Test
  void admissionRetryLaterReturns503WithRetryAfter() throws Exception {
    testSessions.admission = new DaemonAdmission.RetryLater(Duration.ofSeconds(15));
    MockHttpServletRequest request = newRequest();
    MockHttpServletResponse response = new MockHttpServletResponse();

    controller.handle(request, response);

    assertEquals(503, response.getStatus());
    assertEquals("15", response.getHeader("Retry-After"));
    assertNoSecretLeak(response);
  }

  @Test
  void protocolMismatchWithoutReleaseReturns426WithProtobuf() throws Exception {
    MockHttpServletRequest request = newRequest();
    request.removeHeader("KK-Daemon-Protocol");
    request.addHeader("KK-Daemon-Protocol", "99");
    MockHttpServletResponse response = new MockHttpServletResponse();

    controller.handle(request, response);

    assertEquals(426, response.getStatus());
    assertEquals("application/x-protobuf", response.getContentType());
    UpgradeRequired proto = UpgradeRequired.parseFrom(response.getContentAsByteArray());
    assertEquals(ControlProtocolCodec.PROTOCOL_VERSION, proto.getTargetProtocolVersion());
    assertFalse(proto.hasArtifactUrl());
    assertFalse(proto.hasArtifactSha256());
    assertNoSecretLeak(response);
  }

  @Test
  void protocolMismatchWithReleaseReturns426WithArtifactMetadata() throws Exception {
    testReleaseProvider.target =
        new DaemonReleaseTarget(
            "1.0.0",
            "https://github.com/fengwk/kk-studio/releases/download/v1.0.0/kk-daemon-linux-amd64.tar.gz",
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef");

    MockHttpServletRequest request = newRequest();
    request.removeHeader("KK-Daemon-Protocol");
    request.addHeader("KK-Daemon-Protocol", "2");
    request.removeHeader("KK-Daemon-Platform");
    request.addHeader("KK-Daemon-Platform", "linux-amd64");
    MockHttpServletResponse response = new MockHttpServletResponse();

    controller.handle(request, response);

    assertEquals(426, response.getStatus());
    assertEquals("application/x-protobuf", response.getContentType());
    UpgradeRequired proto = UpgradeRequired.parseFrom(response.getContentAsByteArray());
    assertEquals(ControlProtocolCodec.PROTOCOL_VERSION, proto.getTargetProtocolVersion());
    assertTrue(proto.hasArtifactUrl());
    assertEquals(testReleaseProvider.target.artifactUrl(), proto.getArtifactUrl());
    assertTrue(proto.hasArtifactSha256());
    assertEquals(testReleaseProvider.target.artifactSha256(), proto.getArtifactSha256());
    assertNoSecretLeak(response);
  }

  @Test
  void malformedHandshakeThrowsIllegalArgumentReturns400() throws Exception {
    testSessions.admitException =
        new IllegalArgumentException("invalid canonical lowercase UUID instanceId");
    MockHttpServletRequest request = newRequest();
    MockHttpServletResponse response = new MockHttpServletResponse();

    controller.handle(request, response);

    assertEquals(400, response.getStatus());
    assertNoSecretLeak(response);
  }

  @Test
  void malformedInstanceIdInHandshakeReturns400() throws Exception {
    MockHttpServletRequest request = newRequest();
    request.removeHeader("KK-Daemon-Instance");
    request.addHeader("KK-Daemon-Instance", "not-a-canonical-uuid");
    MockHttpServletResponse response = new MockHttpServletResponse();

    controller.handle(request, response);

    assertEquals(400, response.getStatus());
    assertNoSecretLeak(response);
  }

  @Test
  void admissionInternalErrorReturns500() throws Exception {
    testSessions.admitException = new IllegalStateException("database unavailable");
    MockHttpServletRequest request = newRequest();
    MockHttpServletResponse response = new MockHttpServletResponse();

    controller.handle(request, response);

    assertEquals(500, response.getStatus());
    assertNoSecretLeak(response);
  }

  private void assertNoSecretLeak(MockHttpServletResponse response) throws Exception {
    String content = response.getContentAsString();
    assertFalse(
        content.contains(SECRET_TOKEN),
        "Response content must not contain the sensitive registration token");
    for (ILoggingEvent event : logAppender.list) {
      String msg = event.getFormattedMessage();
      assertFalse(
          msg.contains(SECRET_TOKEN),
          "Log message must never leak the sensitive registration token: " + msg);
    }
  }

  private MockHttpServletRequest newRequest() {
    MockHttpServletRequest request =
        new MockHttpServletRequest("GET", EnvironmentDaemonAdmissionController.PATH);
    request.addHeader("Upgrade", "websocket");
    request.addHeader("Connection", "Upgrade");
    request.addHeader("Authorization", "Bearer " + SECRET_TOKEN);
    request.addHeader("KK-Daemon-Instance", INSTANCE_ID);
    request.addHeader("KK-Daemon-Protocol", String.valueOf(ControlProtocolCodec.PROTOCOL_VERSION));
    return request;
  }

  private static final class TestSessions implements DaemonSessions {
    private DaemonAdmission admission = new DaemonAdmission.Admitted(ENV_ID, INSTANCE_ID);
    private RuntimeException admitException;
    private final List<DaemonAdmission.Admitted> abandoned = new ArrayList<>();

    @Override
    public DaemonAdmission admit(DaemonHandshake handshake) {
      if (admitException != null) {
        throw admitException;
      }
      return admission;
    }

    @Override
    public void abandon(DaemonAdmission.Admitted admitted) {
      abandoned.add(admitted);
    }

    @Override
    public DaemonSession open(
        DaemonAdmission.Admitted admitted, DaemonControlChannel control, EnvironmentTunnel tunnel) {
      return null;
    }
  }

  private static final class TestReleaseProvider implements DaemonReleaseProvider {
    private DaemonReleaseTarget target;

    @Override
    public Optional<DaemonReleaseTarget> resolveTarget(
        DaemonOperatingSystem operatingSystem, DaemonArchitecture architecture) {
      return Optional.ofNullable(target);
    }
  }

  private static final class SingleObjectProvider<T> implements ObjectProvider<T> {
    private final T instance;

    SingleObjectProvider(T instance) {
      this.instance = instance;
    }

    @Override
    public T getObject(Object... args) {
      return instance;
    }

    @Override
    public T getIfAvailable() {
      return instance;
    }

    @Override
    public T getIfUnique() {
      return instance;
    }

    @Override
    public T getObject() {
      return instance;
    }
  }
}
