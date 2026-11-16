package fun.fengwk.kkstudio.platform.daemon.protocol;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/** EXECUTION_EXIT_ACK is an invocation-scoped server-to-daemon confirmation. */
class ControlProtocolExecutionExitAckTest {

  @Test
  void acceptsAckWithEnvironmentAndInvocationIdentity() {
    Envelope acknowledgement =
        Envelope.newBuilder()
            .setMessageType(MessageType.EXECUTION_EXIT_ACK)
            .setEnvironmentId(ControlProtocolFixtures.ENVIRONMENT_ID)
            .setInvocationId(ControlProtocolFixtures.INVOCATION_ID)
            .setExecutionExitAck(ExecutionExitAck.getDefaultInstance())
            .build();

    assertDoesNotThrow(() -> ControlProtocolCodec.encodeBinary(acknowledgement));
  }

  @Test
  void rejectsAckWithoutInvocationIdentityOrMatchingPayload() {
    Envelope missingInvocationId =
        Envelope.newBuilder()
            .setMessageType(MessageType.EXECUTION_EXIT_ACK)
            .setEnvironmentId(ControlProtocolFixtures.ENVIRONMENT_ID)
            .setExecutionExitAck(ExecutionExitAck.getDefaultInstance())
            .build();
    Envelope mismatchedPayload =
        Envelope.newBuilder()
            .setMessageType(MessageType.EXECUTION_EXIT_ACK)
            .setEnvironmentId(ControlProtocolFixtures.ENVIRONMENT_ID)
            .setInvocationId(ControlProtocolFixtures.INVOCATION_ID)
            .setExecutionExited(ExecutionExited.getDefaultInstance())
            .build();

    assertThrows(
        ControlProtocolException.class,
        () -> ControlProtocolCodec.encodeBinary(missingInvocationId));
    assertThrows(
        ControlProtocolException.class, () -> ControlProtocolCodec.encodeBinary(mismatchedPayload));
  }
}
