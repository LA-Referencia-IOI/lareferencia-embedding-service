package org.lareferencia.embedding.v1.util;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantLock;

import org.springframework.stereotype.Component;

@Component
public class PerKeyLockManager {

  private final ConcurrentMap<String, LockEntry> locks = new ConcurrentHashMap<>();
  private final java.util.function.Supplier<ReentrantLock> lockFactory;

  public PerKeyLockManager() { this(ReentrantLock::new); }

  // Allows deterministic handoff tests without scheduling hooks in production.
  PerKeyLockManager(java.util.function.Supplier<ReentrantLock> lockFactory) {
    this.lockFactory = lockFactory;
  }

  public LockSet lockAll(Collection<String> keys) {
    List<String> orderedKeys = keys.stream()
        .distinct()
        .sorted(Comparator.naturalOrder())
        .toList();

    List<LockHandle> acquiredLocks = new ArrayList<>(orderedKeys.size());
    for (String key : orderedKeys) {
      // Register the caller before it can wait, so cleanup cannot remove its lock.
      LockEntry entry = locks.compute(key, (ignored, existing) -> {
        LockEntry value = existing == null ? new LockEntry(lockFactory.get()) : existing;
        value.references++;
        return value;
      });
      entry.lock.lock();
      acquiredLocks.add(new LockHandle(key, entry));
    }
    return new LockSet(acquiredLocks);
  }

  public final class LockSet implements AutoCloseable {

    private final List<LockHandle> acquiredLocks;

    private LockSet(List<LockHandle> acquiredLocks) {
      this.acquiredLocks = acquiredLocks;
    }

    @Override
    public void close() {
      for (int index = acquiredLocks.size() - 1; index >= 0; index--) {
        LockHandle handle = acquiredLocks.get(index);
        handle.entry().lock.unlock();
        locks.compute(handle.key(), (key, entry) -> {
          if (entry != handle.entry()) {
            throw new IllegalStateException("Lock ownership changed");
          }
          return --entry.references == 0 ? null : entry;
        });
      }
    }
  }

  private static final class LockEntry {
    final ReentrantLock lock;
    LockEntry(ReentrantLock lock) { this.lock = lock; }
    int references; // Accessed only inside ConcurrentMap.compute.
  }

  private record LockHandle(String key, LockEntry entry) {
  }
}