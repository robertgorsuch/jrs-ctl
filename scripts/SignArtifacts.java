import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Stream;

/**
 * Single-file program (run as {@code java SignArtifacts.java <dir>}) that writes an Ed25519 detached
 * signature {@code <file>.sig} next to every release artifact in {@code <dir>}: the portable
 * archives, their {@code .sha256} sidecars and the SBOM. Spec section 11.1: Ed25519 through the
 * JDK's built-in provider, no BouncyCastle; the key is the Base64 PKCS#8 encoding produced by
 * {@code jrsctl keys generate}, read from {@code JRSCTL_SIGNING_KEY} and never printed. The
 * signature file holds the Base64 signature over the raw file bytes, one line, the same encoding
 * hotfix bundles use, so a customer can verify it with a trusted publisher key. Exit 0 when every
 * artifact was signed, 1 otherwise. The key is CI-only (spec section 0 rule 11); the wrapper
 * scripts skip this program when the variable is unset.
 */
public final class SignArtifacts {

  private static final List<String> SUFFIXES = List.of(".zip", ".tar.gz", ".sha256", "-sbom.json");

  private SignArtifacts() {}

  public static void main(String[] args) throws IOException, GeneralSecurityException {
    if (args.length != 1) {
      System.err.println("usage: java SignArtifacts.java <artifact-dir>");
      System.exit(1);
    }
    String encoded = System.getenv("JRSCTL_SIGNING_KEY");
    if (encoded == null || encoded.isBlank()) {
      System.out.println("signing skipped: no key (CI only)");
      return;
    }
    PrivateKey key;
    try {
      byte[] der = Base64.getDecoder().decode(encoded.strip());
      key = KeyFactory.getInstance("Ed25519").generatePrivate(new PKCS8EncodedKeySpec(der));
    } catch (IllegalArgumentException | GeneralSecurityException e) {
      System.err.println("JRSCTL_SIGNING_KEY is not a Base64 PKCS#8 Ed25519 private key");
      System.exit(1);
      return;
    }
    Path dir = Path.of(args[0]);
    List<Path> artifacts;
    try (Stream<Path> list = Files.list(dir)) {
      artifacts =
          list.filter(Files::isRegularFile)
              .filter(p -> SUFFIXES.stream().anyMatch(s -> p.getFileName().toString().endsWith(s)))
              .filter(p -> !p.getFileName().toString().endsWith(".sig"))
              .sorted()
              .toList();
    }
    if (artifacts.isEmpty()) {
      System.err.println("no artifacts to sign in " + dir);
      System.exit(1);
    }
    for (Path artifact : artifacts) {
      Signature sig = Signature.getInstance("Ed25519");
      sig.initSign(key);
      MessageDigest sha = MessageDigest.getInstance("SHA-256");
      byte[] buf = new byte[65536];
      try (InputStream in = Files.newInputStream(artifact)) {
        for (int n = in.read(buf); n > 0; n = in.read(buf)) {
          sig.update(buf, 0, n);
          sha.update(buf, 0, n);
        }
      }
      String line = Base64.getEncoder().encodeToString(sig.sign()) + "\n";
      Path out = artifact.resolveSibling(artifact.getFileName() + ".sig");
      Files.writeString(out, line, StandardCharsets.UTF_8);
      System.out.println(
          "signed " + artifact.getFileName() + " (sha256 " + HexFormat.of().formatHex(sha.digest())
              + ") -> " + out.getFileName());
    }
  }
}
