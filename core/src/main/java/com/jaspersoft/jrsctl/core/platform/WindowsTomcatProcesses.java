package com.jaspersoft.jrsctl.core.platform;

import static java.util.Objects.requireNonNull;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * {@link TomcatProcessFinder} for Windows, reading {@code Win32_Process} through Windows
 * PowerShell, because the JDK's {@link ProcessHandle.Info} returns no command line for any process
 * there (issue #38, ADR-0014). Invariants: PowerShell runs by absolute path with a fixed script
 * passed as {@code -EncodedCommand}, as an argument list through the {@link ProcessRunner}, so no
 * operator input is ever interpreted; paths and command lines travel Base64-encoded UTF-8, so the
 * console code page cannot mangle them; a scan that exits non-zero, times out or does not print its
 * end marker throws {@link TomcatScanException}, and so does a failure to list listening ports; a
 * {@code java.exe} or {@code javaw.exe} whose command line this account cannot read (another
 * account's process, without elevation) is returned as {@link TomcatProcess#opaque()} with the
 * ports it listens on, which are readable without elevation, so {@link TomcatState} can tell an
 * unrelated Java service from a Tomcat on the watched ports; an unreadable {@code tomcatN.exe} is
 * left out, because service wrappers belong to the service kinds, which do not use this scan;
 * Tomcat recognition is {@link TomcatProcesses#describe}; this JVM is never listed.
 */
final class WindowsTomcatProcesses implements TomcatProcessFinder {

  static final Duration TIMEOUT = Duration.ofSeconds(30);
  static final String END = "END";

  private static final String SCRIPT =
      "$ErrorActionPreference = 'Stop'\n"
          + "$enc = [Text.Encoding]::UTF8\n"
          + "$listen = @{}\n"
          + "Get-NetTCPConnection -State Listen | ForEach-Object {\n"
          + "  $k = [string]$_.OwningProcess\n"
          + "  if (-not $listen.ContainsKey($k)) {"
          + " $listen[$k] = New-Object 'System.Collections.Generic.SortedSet[int]' }\n"
          + "  [void]$listen[$k].Add([int]$_.LocalPort)\n"
          + "}\n"
          + "Get-CimInstance -ClassName Win32_Process -Filter \"Name = 'java.exe' OR Name ="
          + " 'javaw.exe' OR Name LIKE 'tomcat%.exe'\" | ForEach-Object {\n"
          + "  $exe = if ($_.ExecutablePath) {"
          + " [Convert]::ToBase64String($enc.GetBytes($_.ExecutablePath)) } else { '-' }\n"
          + "  $cmd = if ($_.CommandLine) {"
          + " [Convert]::ToBase64String($enc.GetBytes($_.CommandLine)) } else { '-' }\n"
          + "  $k = [string]$_.ProcessId\n"
          + "  $ports = if ($listen.ContainsKey($k)) { $listen[$k] -join ',' } else { '-' }\n"
          + "  'P {0} {1} {2} {3} {4}' -f $_.ProcessId, $_.Name, $exe, $cmd, $ports\n"
          + "}\n"
          + "'"
          + END
          + "'\n";

  private static final Pattern SPACE = Pattern.compile(" ");
  private static final Pattern COMMA = Pattern.compile(",");
  private static final Pattern JVM = Pattern.compile("(?i)javaw?\\.exe");

  private final ProcessRunner runner;
  private final List<String> command;

  WindowsTomcatProcesses(ProcessRunner runner) {
    this(runner, System.getenv("SystemRoot"));
  }

  WindowsTomcatProcesses(ProcessRunner runner, String systemRoot) {
    this.runner = requireNonNull(runner, "runner");
    this.command = command(systemRoot);
  }

  /** {@code powershell.exe} by absolute path, so a minimal {@code PATH} cannot hide it. */
  static List<String> command(String systemRoot) {
    String root = systemRoot == null || systemRoot.isBlank() ? "C:\\Windows" : systemRoot.strip();
    String encoded = Base64.getEncoder().encodeToString(SCRIPT.getBytes(StandardCharsets.UTF_16LE));
    return List.of(
        root + "\\System32\\WindowsPowerShell\\v1.0\\powershell.exe",
        "-NoLogo",
        "-NoProfile",
        "-NonInteractive",
        "-EncodedCommand",
        encoded);
  }

  List<String> command() {
    return command;
  }

  @Override
  public List<TomcatProcess> find() {
    List<String> stdout = new ArrayList<>();
    List<String> stderr = new ArrayList<>();
    ProcessRunner.Result result;
    try {
      result =
          runner.run(
              new ProcessRunner.Request(command, Optional.empty(), Map.of(), TIMEOUT),
              line -> {
                if (line.stream() == ProcessRunner.OutputLine.Stream.STDOUT) {
                  stdout.add(line.text());
                } else if (stderr.size() < 5) {
                  stderr.add(line.text());
                }
              });
    } catch (RuntimeException e) {
      throw new TomcatScanException("cannot run the Win32_Process scan: " + e.getMessage());
    }
    if (result.timedOut()) {
      throw new TomcatScanException(
          "the Win32_Process scan did not finish within " + TIMEOUT.toSeconds() + " s");
    }
    if (result.exitCode() != 0 || stdout.stream().noneMatch(l -> l.strip().equals(END))) {
      throw new TomcatScanException(
          "the Win32_Process scan failed (exit "
              + result.exitCode()
              + ")"
              + (stderr.isEmpty() ? "" : ": " + String.join(" ", stderr).strip()));
    }
    return parse(stdout, ProcessHandle.current().pid());
  }

  /** The processes in the scan's output, excluding {@code self}. */
  static List<TomcatProcess> parse(List<String> lines, long self) {
    List<TomcatProcess> found = new ArrayList<>();
    for (String raw : lines) {
      String line = raw.strip();
      if (!line.startsWith("P ")) {
        continue;
      }
      String[] fields = SPACE.split(line, -1);
      if (fields.length != 6) {
        continue;
      }
      Set<Integer> ports = ports(fields[5]);
      long pid;
      try {
        pid = Long.parseLong(fields[1]);
      } catch (NumberFormatException e) {
        continue;
      }
      if (pid == self) {
        continue;
      }
      String name = fields[2].toLowerCase(Locale.ROOT);
      Optional<String> exe = decode(fields[3]);
      Optional<String> commandLine = decode(fields[4]);
      if (commandLine.isPresent()) {
        TomcatProcesses.describe(pid, commandLine.get(), exe.orElse(""))
            .map(t -> t.withListeningPorts(ports))
            .ifPresent(found::add);
      } else if (exe.isPresent()) {
        TomcatProcesses.describe(pid, "", exe.get())
            .map(t -> t.withListeningPorts(ports))
            .ifPresent(found::add);
      } else if (JVM.matcher(name).matches()) {
        found.add(
            new TomcatProcess(
                pid, "", Optional.empty(), Optional.empty(), Optional.<Path>empty(), ports));
      }
    }
    return List.copyOf(found);
  }

  private static Set<Integer> ports(String field) {
    if (field.equals("-") || field.isBlank()) {
      return Set.of();
    }
    Set<Integer> ports = new TreeSet<>();
    for (String part : COMMA.split(field, -1)) {
      try {
        int port = Integer.parseInt(part.strip());
        if (port > 0 && port <= 65535) {
          ports.add(port);
        }
      } catch (NumberFormatException e) {
        // a malformed entry is skipped; the rest of the row still counts
      }
    }
    return Set.copyOf(ports);
  }

  private static Optional<String> decode(String field) {
    if (field.equals("-") || field.isEmpty()) {
      return Optional.empty();
    }
    try {
      String text = new String(Base64.getDecoder().decode(field), StandardCharsets.UTF_8);
      return text.isBlank() ? Optional.empty() : Optional.of(text);
    } catch (IllegalArgumentException e) {
      return Optional.empty();
    }
  }
}
