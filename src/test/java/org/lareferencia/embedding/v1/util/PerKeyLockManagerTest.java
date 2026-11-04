package org.lareferencia.embedding.v1.util;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class PerKeyLockManagerTest {
  @Test void overlappingBatchesRemainMutuallyExclusiveAcrossRepeatedHandoffs() throws Exception {
    var manager = new PerKeyLockManager();
    var active = new AtomicInteger();
    var start = new CyclicBarrier(8);
    try (var executor = Executors.newFixedThreadPool(8)) {
      var tasks = java.util.stream.IntStream.range(0, 8).mapToObj(index -> executor.submit(() -> {
        start.await(5, TimeUnit.SECONDS);
        for (int i = 0; i < 2000; i++) {
          try (var ignored = manager.lockAll(index % 2 == 0 ? List.of("b", "a", "a") : List.of("a", "c"))) {
            assertEquals(1, active.incrementAndGet());
            Thread.yield();
            assertEquals(0, active.decrementAndGet());
          }
        }
        return null;
      })).toList();
      for (var task : tasks) task.get(20, TimeUnit.SECONDS);
    }
  }

  @Test void releasingOwnerCannotRemoveLockAlreadyAcquiredBySuccessor() throws Exception {
    var ownerHolding=new CountDownLatch(1);var releaseOwner=new CountDownLatch(1);
    var successorHolding=new CountDownLatch(1);var releaseSuccessor=new CountDownLatch(1);
    var firstUnlock=new java.util.concurrent.atomic.AtomicBoolean(true);
    var firstLock=new java.util.concurrent.atomic.AtomicReference<java.util.concurrent.locks.ReentrantLock>();
    var manager=new PerKeyLockManager(()->{
      var lock=new java.util.concurrent.locks.ReentrantLock() {
        @Override public void unlock() {
          super.unlock();
          if(firstUnlock.compareAndSet(true,false)) await(successorHolding);
        }
      };
      firstLock.compareAndSet(null,lock);return lock;
    });
    try(var executor=Executors.newFixedThreadPool(3)) {
      var owner=executor.submit(()->{
        try(var ignored=manager.lockAll(List.of("same"))) { ownerHolding.countDown();await(releaseOwner); }
      });
      assertTrue(ownerHolding.await(2,TimeUnit.SECONDS));
      var successor=executor.submit(()->{
        try(var ignored=manager.lockAll(List.of("same"))) { successorHolding.countDown();await(releaseSuccessor); }
      });
      org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(2))
          .until(()->firstLock.get().hasQueuedThreads());
      releaseOwner.countDown();owner.get(2,TimeUnit.SECONDS);
      var third=executor.submit(()->{try(var ignored=manager.lockAll(List.of("same"))) { return true; }});
      try {
        assertThrows(TimeoutException.class,()->third.get(200,TimeUnit.MILLISECONDS));
      } finally { releaseSuccessor.countDown(); }
      assertTrue(third.get(2,TimeUnit.SECONDS));successor.get(2,TimeUnit.SECONDS);
    } finally { releaseOwner.countDown();releaseSuccessor.countDown(); }
  }
  private static void await(CountDownLatch latch) {
    try { if(!latch.await(5,TimeUnit.SECONDS)) throw new IllegalStateException("Handoff timed out"); }
    catch(InterruptedException e) { Thread.currentThread().interrupt();throw new IllegalStateException(e); }
  }
}
