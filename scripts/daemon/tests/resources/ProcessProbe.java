import fun.fengwk.kkstudio.harness.daemon.DaemonArguments;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;

/**
 * Non-mutating native fixture: distinct streams, pipe pressure, decoded argv, and JAR version gates.
 *
 * <p>Every mode runs on the production {@link DaemonArguments#decode(String[])} contract, so the
 * installer serializer and the daemon decoder are exercised together instead of a copied decoder.
 * Echo prints Base64 of each decoded token, which keeps the assertion transport pure ASCII.
 */
class ProcessProbe {
  public static void main(String[] args) throws Exception {
    if (args.length == 0) {
      throw new IllegalArgumentException("expected a mode argument");
    }
    // Exactly the daemon entry contract: Base64 tokens after --base64-args, otherwise as-is.
    String[] decoded = DaemonArguments.decode(args);
    switch (decoded[0]) {
      case "echo":
        for (String argument : Arrays.copyOfRange(decoded, 1, decoded.length)) {
          System.out.println(
              Base64.getEncoder().encodeToString(argument.getBytes(StandardCharsets.UTF_8)));
        }
        break;
      case "fail":
        System.out.print("stdout before failure");
        System.err.print("stderr before failure");
        System.exit(23);
        break;
      case "flood":
        // Alternating writes exceed either pipe's buffer; sequential readers would deadlock.
        for (int i = 0; i < 256; i++) {
          System.out.print("o".repeat(8192));
          System.err.print("e".repeat(8192));
        }
        break;
      case "--version":
        try (var stream = ProcessProbe.class.getResourceAsStream("/version-mode.txt")) {
          String mode = new String(stream.readAllBytes(), StandardCharsets.UTF_8).trim();
          if (!mode.equals("stderr-only")) {
            System.out.println(mode.equals("wrong-stdout") ? "unexpected" : "kk-studio-daemon fixture");
          }
          System.err.println("kk-studio-daemon diagnostic");
          if (mode.equals("fail")) {
            System.exit(23);
          }
        }
        break;
      default:
        throw new IllegalArgumentException(decoded[0]);
    }
  }
}
