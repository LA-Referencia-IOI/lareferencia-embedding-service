package org.lareferencia.embedding.util;

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

  private final ConcurrentMap<String, ReentrantLock> locks = new ConcurrentHashMap<>();

  public LockSet lockAll(Collection<String> keys) {
    List<String> orderedKeys = keys.stream()
        .distinct()
        .sorted(Comparator.naturalOrder())
        .toList();

    List<LockHandle> acquiredLocks = new ArrayList<>(orderedKeys.size());
    for (String key : orderedKeys) {
      ReentrantLock lock = locks.computeIfAbsent(key, ignored -> new ReentrantLock());
      lock.lock();
      acquiredLocks.add(new LockHandle(key, lock));
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
        handle.lock().unlock();
        if (!handle.lock().hasQueuedThreads()) {
          locks.remove(handle.key(), handle.lock());
        }
      }
    }
  }

  private record LockHandle(String key, ReentrantLock lock) {
  }
}