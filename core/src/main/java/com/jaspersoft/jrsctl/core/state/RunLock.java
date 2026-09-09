package com.jaspersoft.jrsctl.core.state;

import com.jaspersoft.jrsctl.core.JrsctlHome;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Optional;

/**
 * The exclusive run lock on {@code $JRSCTL_HOME/runs.lock} (spec §5.5). Invariants: the lock is an
 * OS file lock so it is released even when the process dies; while held the file contains {@code
 * runId pid startedAt} so a contender can name the holder; the locked byte range lies far beyond
 * the content so the holder text stays readable by other processes; contention in another process
 * ({@code tryLock() == null}) and in the same JVM ({@link OverlappingFileLockException}) both
 * surface as {@link LockHeldException}; {@link #close()} truncates and releases.
 */
public final class RunLock implements AutoCloseable {

  private static final long LOCK_POSITION = 1L << 40;

  private final Path file;
  private final FileChannel channel;
  private final FileLock lock;
  private final String runId;

  public RunLock(JrsctlHome home, String runId, Instant startedAt) {
    this.file = home.runLock();
    this.runId = runId;
    FileChannel ch = null;
    FileLock acquired = null;
    try {
      Path parent = file.toAbsolutePath().getParent();
      if (parent != null) {
        Files.createDirectories(parent);
      }
      ch =
          FileChannel.open(
              file, StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
      try {
        acquired = ch.tryLock(LOCK_POSITION, 1, false);
      } catch (OverlappingFileLockException e) {
        acquired = null;
      }
      if (acquired == null) {
        Optional<Holder> holder = readHolder(file);
        closeQuietly(ch);
        throw new LockHeldException(
            holder.map(Holder::runId).orElse("unknown"), holder.map(Holder::pid).orElse("unknown"));
      }
      String text = runId + " " + ProcessHandle.current().pid() + " " + startedAt;
      ch.truncate(0);
      ByteBuffer buf = ByteBuffer.wrap(text.getBytes(StandardCharsets.UTF_8));
      while (buf.hasRemaining()) {
        ch.write(buf);
      }
      ch.force(true);
    } catch (IOException e) {
      if (acquired != null) {
        try {
          acquired.release();
        } catch (IOException suppressed) {
          e.addSuppressed(suppressed);
        }
      }
      closeQuietly(ch);
      throw new IllegalStateException("cannot acquire run lock " + file, e);
    }
    this.channel = ch;
    this.lock = acquired;
  }

  public String runId() {
    return runId;
  }

  public Path file() {
    return file;
  }

  /** Parses {@code runId pid startedAt} from the lock file; empty when unreadable or blank. */
  public static Optional<Holder> readHolder(Path lockFile) {
    try {
      if (!Files.exists(lockFile)) {
        return Optional.empty();
      }
      String text = Files.readString(lockFile, StandardCharsets.UTF_8).strip();
      if (text.isEmpty()) {
        return Optional.empty();
      }
      String[] parts = text.split(" ", -1);
      String id = parts[0];
      String pid = parts.length > 1 ? parts[1] : "unknown";
      String started = parts.length > 2 ? parts[2] : "";
      return Optional.of(new Holder(id, pid, started));
    } catch (IOException e) {
      return Optional.empty();
    }
  }

  @Override
  public void close() {
    IOException failure = null;
    try {
      channel.truncate(0);
      channel.force(true);
    } catch (IOException e) {
      failure = e;
    }
    try {
      lock.release();
    } catch (IOException e) {
      if (failure == null) {
        failure = e;
      } else {
        failure.addSuppressed(e);
      }
    }
    try {
      channel.close();
    } catch (IOException e) {
      if (failure == null) {
        failure = e;
      } else {
        failure.addSuppressed(e);
      }
    }
    if (failure != null) {
      throw new IllegalStateException("cannot release run lock " + file, failure);
    }
  }

  private static void closeQuietly(FileChannel ch) {
    if (ch == null) {
      return;
    }
    try {
      ch.close();
    } catch (IOException e) {
      // best effort: the lock was never acquired, nothing to release
    }
  }

  /** What the lock file says about the current holder. */
  public record Holder(String runId, String pid, String startedAt) {}
}
