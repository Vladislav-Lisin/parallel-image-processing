package threadPool;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * CustomThreadPool
 */
public class CustomThreadPool{
    private final Thread[] threadPool;
    private final Deque<Task> queue = new ArrayDeque<>();
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition hasWork = lock.newCondition(); 
    private final Condition waitAllThreads = lock.newCondition(); 
    private final AtomicInteger remainingTasks = new AtomicInteger(0);
    private final Map<TaskType, AtomicLong> taskTimesNanos = new EnumMap<>(TaskType.class);
    private boolean isShutdown = false;

    public CustomThreadPool(int threadsCount) {
        for (TaskType type : TaskType.values()) {
            taskTimesNanos.put(type, new AtomicLong(0));
        }
        this.threadPool = new Thread[threadsCount];
        for (int i = 0; i < threadsCount; i++) {
            threadPool[i] = new Thread(this::workerLoop, "Worker-" + i);
            threadPool[i].start();
        }
    }

    public CustomThreadPool() {
        this(Runtime.getRuntime().availableProcessors());
    }

    private void workerLoop() {
        while (true) {
            Task task;
            lock.lock();
            try {
                while (queue.isEmpty() && !isShutdown) {
                    hasWork.await();
                }
                if (queue.isEmpty() && isShutdown) {
                    return;
                }
                task = queue.pollFirst();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } finally {
                lock.unlock();
            }

            long t0 = System.nanoTime();
            task.run();
            long dt = System.nanoTime() - t0;
            taskTimesNanos.get(task.getTaskType()).addAndGet(dt);

            for (Task child : task.getChilds()) {
                if (child.delDependenci() == 0) {
                    addTask(child);
                }
            }

            if (decrementRemainingTask() == 0) {
                lock.lock();
                try {
                    waitAllThreads.signalAll();
                } finally {
                    lock.unlock();
                }
            }
        }
    }

    public void awaitCompletion() {
        lock.lock();
        try {
            while (remainingTasks.get() > 0) {
                waitAllThreads.await();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            lock.unlock();
        }
    }

    public void shutdown() {
        setIsShutdown(true);
        for (Thread worker : threadPool) {
            try {
                worker.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    public long getTaskTimeMillis(TaskType type) {
        return taskTimesNanos.get(type).get() / 1_000_000L;
    }

    public void setRemainingTask(int count){
        remainingTasks.set(count);
    }

    public int getRemainingTask(){
        return remainingTasks.get();
    }

    public int decrementRemainingTask(){
        return remainingTasks.decrementAndGet();
    }

    public void addTask(Task task){
        lock.lock();
        try {
            queue.offerLast(task);
            hasWork.signal();
        } finally {
            lock.unlock();
        }
    }

    public boolean isShutdown(){
        lock.lock();
        try {
            return isShutdown;
        } finally {
            lock.unlock();
        }
    }

    public void setIsShutdown(boolean don){
        lock.lock();
        try {
            isShutdown = don;
            hasWork.signalAll();
        } finally {
            lock.unlock();
        }
    }
}
