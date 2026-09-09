package com.jaspersoft.jrsctl.app;

import com.jaspersoft.jrsctl.core.crypto.Ed25519;
import com.jaspersoft.jrsctl.core.keys.KeyRing;
import com.jaspersoft.jrsctl.core.redact.Redactor;
import com.jaspersoft.jrsctl.ops.Services;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.PublicKey;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

/**
 * {@code jrsctl keys list|add|remove|generate} over the trusted key ring (spec §11.1). Invariants:
 * only public keys ever enter {@code keys/trusted}; {@code generate} writes the private key once,
 * owner-only, to the file the operator names and prints the {@code file:} reference to use with
 * {@code hotfix build}, never the key itself; the bundled publisher key can be neither replaced nor
 * removed; every change is audited.
 */
@Command(
    name = "keys",
    mixinStandardHelpOptions = true,
    exitCodeOnInvalidInput = ExitCodes.USAGE,
    description = "Manage the public keys trusted to sign hotfix bundles.",
    subcommands = {
      KeysCommand.ListKeys.class,
      KeysCommand.Add.class,
      KeysCommand.Remove.class,
      KeysCommand.Generate.class
    })
final class KeysCommand implements Runnable {

  @Spec CommandSpec spec;

  @Override
  public void run() {
    spec.commandLine().usage(spec.commandLine().getOut());
  }

  /** {@code jrsctl keys list [--json]}. */
  @Command(
      name = "list",
      mixinStandardHelpOptions = true,
      exitCodeOnInvalidInput = ExitCodes.USAGE,
      description = "List trusted keys with their fingerprints.")
  static final class ListKeys implements Callable<Integer> {

    @Spec CommandSpec spec;
    @Mixin GlobalOptions global;

    @Override
    public Integer call() {
      PrintWriter out = spec.commandLine().getOut();
      try (Bootstrap boot = Bootstrap.open(global, Env.vars(), Clock.systemUTC())) {
        List<KeyRing.TrustedKey> keys = new KeyRing(boot.services().home()).list();
        if (global.json()) {
          List<Map<String, Object>> rows = new ArrayList<>();
          for (KeyRing.TrustedKey k : keys) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", k.name());
            row.put("fingerprint", k.fingerprint());
            row.put("bundled", k.bundled());
            row.put("publicKey", Ed25519.encodePublic(k.key()));
            rows.add(row);
          }
          JsonOut.print(out, rows);
          return ExitCodes.SUCCESS;
        }
        if (keys.isEmpty()) {
          out.println("no trusted keys (the publisher key is injected in CI builds only)");
          out.flush();
          return ExitCodes.SUCCESS;
        }
        TextTable table = new TextTable();
        for (KeyRing.TrustedKey k : keys) {
          table.row(k.name(), k.fingerprint(), k.bundled() ? "bundled" : "customer");
        }
        for (String line : table.lines()) {
          out.println(line);
        }
        out.flush();
        return ExitCodes.SUCCESS;
      }
    }
  }

  /** {@code jrsctl keys add <name> <publicKeyFile>}. */
  @Command(
      name = "add",
      mixinStandardHelpOptions = true,
      exitCodeOnInvalidInput = ExitCodes.USAGE,
      description = "Trust a public key (one base64 line, X.509 SubjectPublicKeyInfo).")
  static final class Add implements Callable<Integer> {

    @Spec CommandSpec spec;
    @Mixin GlobalOptions global;

    @Parameters(index = "0", paramLabel = "<name>", description = "Key name, e.g. customer.")
    String name;

    @Parameters(index = "1", paramLabel = "<publicKeyFile>", description = "File with the key.")
    Path file;

    @Override
    public Integer call() throws IOException {
      PrintWriter out = spec.commandLine().getOut();
      PrintWriter err = spec.commandLine().getErr();
      try (Bootstrap boot = Bootstrap.open(global, Env.vars(), Clock.systemUTC())) {
        Services services = boot.services();
        PublicKey key;
        try {
          key = Ed25519.decodePublic(Files.readString(file, StandardCharsets.US_ASCII));
        } catch (IllegalArgumentException e) {
          return ExitCodes.fail(
              out,
              err,
              global.json(),
              ExitCodes.PRECHECK_FAILED,
              file + " is not an Ed25519 public key (" + e.getMessage() + ")");
        }
        KeyRing ring = new KeyRing(services.home());
        KeyRing.TrustedKey added;
        try {
          added = ring.add(name, key);
        } catch (IllegalArgumentException e) {
          return ExitCodes.fail(out, err, global.json(), ExitCodes.USAGE, e.getMessage());
        }
        services.stateStore().get().audit("operator", "keys.add", name + " " + added.fingerprint());
        if (global.json()) {
          Map<String, Object> row = new LinkedHashMap<>();
          row.put("name", name);
          row.put("fingerprint", added.fingerprint());
          JsonOut.print(out, row);
        } else {
          out.println("added key " + name + " (fingerprint " + added.fingerprint() + ")");
          out.flush();
        }
        return ExitCodes.SUCCESS;
      }
    }
  }

  /** {@code jrsctl keys remove <name>}. */
  @Command(
      name = "remove",
      mixinStandardHelpOptions = true,
      exitCodeOnInvalidInput = ExitCodes.USAGE,
      description = "Stop trusting a customer key.")
  static final class Remove implements Callable<Integer> {

    @Spec CommandSpec spec;
    @Mixin GlobalOptions global;

    @Parameters(index = "0", paramLabel = "<name>", description = "Key name.")
    String name;

    @Override
    public Integer call() {
      PrintWriter out = spec.commandLine().getOut();
      PrintWriter err = spec.commandLine().getErr();
      try (Bootstrap boot = Bootstrap.open(global, Env.vars(), Clock.systemUTC())) {
        Services services = boot.services();
        boolean removed;
        try {
          removed = new KeyRing(services.home()).remove(name);
        } catch (IllegalArgumentException e) {
          return ExitCodes.fail(out, err, global.json(), ExitCodes.USAGE, e.getMessage());
        }
        if (!removed) {
          return ExitCodes.fail(
              out,
              err,
              global.json(),
              ExitCodes.PRECHECK_FAILED,
              "no trusted key named " + name,
              Optional.of("see `jrsctl keys list`"));
        }
        services.stateStore().get().audit("operator", "keys.remove", name);
        if (global.json()) {
          Map<String, Object> row = new LinkedHashMap<>();
          row.put("name", name);
          row.put("removed", true);
          JsonOut.print(out, row);
        } else {
          out.println("removed key " + name);
          out.flush();
        }
        return ExitCodes.SUCCESS;
      }
    }
  }

  /** {@code jrsctl keys generate <name> --private-out <file>}. */
  @Command(
      name = "generate",
      mixinStandardHelpOptions = true,
      exitCodeOnInvalidInput = ExitCodes.USAGE,
      description =
          "Generate an Ed25519 pair: trust the public key, write the private key owner-only.")
  static final class Generate implements Callable<Integer> {

    @Spec CommandSpec spec;
    @Mixin GlobalOptions global;

    @Parameters(index = "0", paramLabel = "<name>", description = "Key name, e.g. customer.")
    String name;

    @Option(
        names = "--private-out",
        required = true,
        paramLabel = "<file>",
        description = "Where to write the private key (base64 PKCS#8, one line, owner-only).")
    Path privateOut;

    @Override
    public Integer call() throws IOException {
      PrintWriter out = spec.commandLine().getOut();
      PrintWriter err = spec.commandLine().getErr();
      try (Bootstrap boot = Bootstrap.open(global, Env.vars(), Clock.systemUTC())) {
        Services services = boot.services();
        KeyRing ring = new KeyRing(services.home());
        if (ring.find(name).isPresent()) {
          return ExitCodes.fail(
              out,
              err,
              global.json(),
              ExitCodes.PRECHECK_FAILED,
              "a trusted key named " + name + " already exists",
              Optional.of("remove it first"));
        }
        KeyPair pair = Ed25519.generate();
        Path target = privateOut.toAbsolutePath().normalize();
        try {
          OwnerOnlyFiles.write(
              services.platform(), target, Ed25519.encodePrivate(pair.getPrivate()) + "\n");
        } catch (FileAlreadyExistsException e) {
          return ExitCodes.fail(
              out,
              err,
              global.json(),
              ExitCodes.PRECHECK_FAILED,
              target + " already exists; refusing to overwrite a private key");
        }
        KeyRing.TrustedKey added = ring.add(name, pair.getPublic());
        services
            .stateStore()
            .get()
            .audit("operator", "keys.generate", name + " " + added.fingerprint());
        if (global.json()) {
          Map<String, Object> row = new LinkedHashMap<>();
          row.put("name", name);
          row.put("fingerprint", added.fingerprint());
          row.put("privateKeyFile", target.toString());
          row.put("keyRef", "file:" + target);
          JsonOut.print(out, row);
          return ExitCodes.SUCCESS;
        }
        out.println(
            Redactor.global()
                .redact("generated key " + name + " (fingerprint " + added.fingerprint() + ")"));
        out.println("private key written to " + target + " (owner-only)");
        out.println("sign bundles with: jrsctl hotfix build <dir> --key file:" + target);
        out.flush();
        return ExitCodes.SUCCESS;
      }
    }
  }
}
