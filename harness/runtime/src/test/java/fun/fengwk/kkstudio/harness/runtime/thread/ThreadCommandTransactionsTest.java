package fun.fengwk.kkstudio.harness.runtime.thread;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

class ThreadCommandTransactionsTest {

  @Test
  void stopResultDefensivelyCopiesCancelledInputs() {
    ThreadInput input =
        new ThreadInput(
            1L,
            2L,
            1L,
            ThreadInputType.USER_MESSAGE,
            () -> ThreadInputType.USER_MESSAGE,
            "message-1",
            InputStatus.CANCELLED,
            Instant.EPOCH,
            null);
    List<ThreadInput> source = new ArrayList<>(List.of(input));
    ThreadCommandTransactions.StopResult result =
        new ThreadCommandTransactions.StopResult(1L, source);

    source.clear();

    assertEquals(List.of(input), result.cancelledInputs());
    assertThrows(UnsupportedOperationException.class, () -> result.cancelledInputs().clear());
    assertThrows(
        NullPointerException.class, () -> new ThreadCommandTransactions.StopResult(1L, null));
  }
}
