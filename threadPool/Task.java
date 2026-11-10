package threadPool;


import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public class Task implements  Runnable{

    private final TaskType taskType;
    private final Runnable action;
    private AtomicInteger pendingDependencies = new AtomicInteger(0);
    private List<Task> childTasks = new ArrayList<>();
    public void run(){
        action.run();        
    }


    public Task(TaskType taskType, Runnable action){
        this.taskType = taskType;
        this.action = action;
    }

    public void addChild(Task child){
        childTasks.add(child);
        child.addDependencies();;
    }
    
    public int delDependenci(){
        return pendingDependencies.decrementAndGet();
    }

    public boolean isReady(){
        return pendingDependencies.get() == 0;
    }
    
    public void addDependencies(){
        pendingDependencies.incrementAndGet();
    }
    
    public TaskType getTaskType(){
        return taskType;
    }

    public List<Task> getChilds(){
        return childTasks;
    }
}
