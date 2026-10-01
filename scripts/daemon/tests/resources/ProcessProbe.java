import java.nio.charset.StandardCharsets;
import java.util.Base64;

/** Non-mutating native fixture: distinct streams, pipe pressure, argv, and JAR version gates. */
class ProcessProbe {
  public static void main(String[] args) throws Exception {
    switch (args[0]) {
      case "echo":
        for (int i = 1; i < args.length; i++) {
          System.out.println(
              Base64.getEncoder().encodeToString(args[i].getBytes(StandardCharsets.UTF_8)));
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
        throw new IllegalArgumentException(args[0]);
    }
  }
}
