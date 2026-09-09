package com.jaspersoft.jrsctl.core.platform;

import static java.util.Objects.requireNonNull;

import java.util.Locale;

/**
 * Factory for the {@link Platform} implementation matching the running operating system. Invariant:
 * {@link #detect()} never fails; an operating system that is neither Windows nor Linux is treated
 * as Linux (POSIX semantics) so diagnostics still run, and the x86-64 restriction (ADR 0002) is
 * reported through {@link Platform#arch()} rather than enforced here.
 */
public final class Platforms {

  private Platforms() {}

  /** Platform for this machine with a non-interactive operator prompt. */
  public static Platform detect() {
    return detect(OperatorPrompt.nonInteractive());
  }

  /** Platform for this machine, routing manual-service instructions to {@code prompt}. */
  public static Platform detect(OperatorPrompt prompt) {
    Platform.OsFamily os = osFamily(System.getProperty("os.name", ""));
    Platform.Arch arch = arch(System.getProperty("os.arch", ""));
    ProcessRunner runner = new DefaultProcessRunner();
    return switch (os) {
      case WINDOWS -> new WindowsPlatform(arch, runner, new WindowsFileOps(), prompt);
      case LINUX -> new LinuxPlatform(arch, runner, new LinuxFileOps(), prompt);
    };
  }

  /**
   * Builds a platform of the given family with substituted collaborators, for tests that need a
   * fake {@link ProcessRunner} or {@link FileOps} regardless of the host OS.
   */
  public static Platform forTesting(
      Platform.OsFamily os,
      Platform.Arch arch,
      ProcessRunner runner,
      FileOps files,
      OperatorPrompt prompt) {
    requireNonNull(os, "os");
    return switch (os) {
      case WINDOWS -> new WindowsPlatform(arch, runner, files, prompt);
      case LINUX -> new LinuxPlatform(arch, runner, files, prompt);
    };
  }

  /** Maps an {@code os.name} value to a family; anything that is not Windows counts as Linux. */
  public static Platform.OsFamily osFamily(String osName) {
    return osName.toLowerCase(Locale.ROOT).contains("win")
        ? Platform.OsFamily.WINDOWS
        : Platform.OsFamily.LINUX;
  }

  /** Maps an {@code os.arch} value ({@code amd64}, {@code x86_64}) to an architecture. */
  public static Platform.Arch arch(String osArch) {
    String a = osArch.toLowerCase(Locale.ROOT);
    return a.equals("amd64") || a.equals("x86_64") || a.equals("x64")
        ? Platform.Arch.X86_64
        : Platform.Arch.OTHER;
  }
}
