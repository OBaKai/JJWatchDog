## JJWatchDog - 监控耗时任务、监控死锁甚至监控ANR

**Realize monitoring time-consuming tasks, deadlock and ANR in Android**



#### 介绍

灵感来源自 **SystemServer WatchDog机制**

SystemServer WatchDog机制：主要是用来监控系统服务是否因为耗时任务、死锁等原因出现卡死。如果卡死超过最大容忍时间，SystemServer将会重启（framework层重启）。


本项目很大程度上参考了SystemServer WatchDog的实现，并且根据App的实际场景进行了改造以及丰富。

**监控子线程任务**：JJWatchDog会每隔一段时间发送消息到监控的Handler中，如果不能在规定时间内执行发送的消息，则会报警。

**主线程监控**：JJWatchDog会默认监控主线程，跟监控子线程一样也是每隔一段时间发送消息。可以用来监控ANR但是并不一定准确。不过可以用来做个参考，因为主线程中有耗时任务也是很危险的。

**监控死锁**：JJWatchDog会专门一个线程来检查锁，并且这个线程也会加入到线程监控中。只要在Monitor的实现中获取一下锁即可实现锁监控。如果不能在规定时间内执行发送的消息，则会报警。



#### Introduce
Inspired by SystemServer WatchDog mechanism.
SystemServer WatchDog mechanism: it is mainly used to monitor whether system services are stuck due to time-consuming tasks, deadlocks and other reasons. If the jam exceeds the maximum tolerance time, the system server will restart (framework layer restart).

This project largely refers to the implementation of SystemServer WatchDog, and has been transformed and enriched according to the actual scenario of the app.

**Monitoring thread**: JJWatchDog will send messages to the monitored handler at regular intervals. If the message cannot be executed within the specified time, an alarm will be given.

**Monitoring Main thread**: JJWatchDog will monitor the main thread by default. Like monitoring sub threads, it also sends messages at regular intervals. It can be used to monitor anr, but it is not necessarily accurate. However, it can be used as a reference, because it is also dangerous to have time-consuming tasks in the main thread.

**Monitoring Deadlock**:JJWatchDog will use a thread to check the lock, and this thread will also be added to thread monitoring. Lock monitoring can be realized as long as the lock is obtained in the implementation of monitor. If the message sent cannot be executed within the specified time, an alarm will be given.



#### 依赖方式（Dependencies）

```java
dependencies {
	implementation 'com.github.OBaKai:JJWatchDog:latest.release'
}
```



```java
allprojects {
	repositories {
		...
		maven { url 'https://jitpack.io' }
	}
}
```



#### 使用方法（Usage）

```java
JJWatchDog.get()
	.setCheckTimeInterval(5 * 1000)
	.customMainThreadChecker(5 * 1000)
	.isPrintLog(BuildConfig.DEBUG)
	.setWatchDogListener(new JJWatchDog.WatchDogListener() {
		@Override
		public void onThreadBlocked(String threadName, JJWatchDog.WatchDogThrowable throwable) {
			Log.e("WatchDog_Log", "onThreadBlocked " + threadName);
                        throwable.printStackTrace();
                }

                @Override
                public void onHandleMessageOverdue(String messageInfo) {
			Log.e("WatchDog_Log", "onHandleMessageOverdue " + messageInfo);
                }
	})
	.loop();
```



```java
//添加子线程监控
JJWatchDog.get().addThread(handler);
```



```java
//添加死锁监控
JJWatchDog.get().addMonitor(() -> {
	//获取一下需要监控的锁
	synchronized (lock){ }
});
```

#### SystemServer Watchdog实现原理
```java
原理总结：
1、Watchdog一个单例类，也是一个线程。在SystemServer中会启动它；
2、它维护着一个HandlerChecker列表，而HandlerChecker里边又维护了一个Monitor列表；
3、HandlerChecker是用来检查Handler是否有消息阻塞，Monitor是用来检测线程是否有死锁；
4、Watchdog会有一个专门检测线程死锁的HandlerChecker（mMonitorChecker），也会加入到HandlerChecker列表里边；
5、Watchdog线程会每30s遍历一次HandlerChecker列表发送检查事件。然后统计是否有检查未完成状态的HandlerChecker；
6、如果出现未检查完成的的HandlerChecker，超过60s之后就会dump堆栈日志以及重启SystemServer；
7、当Handler执行HandlerChecker的事件之后就认为检查完成，然后HandlerChecker就会对Monitor列表进行死锁检查；
8、如果出现死锁，那么HandlerChecker的Handler所在的线程就会阻塞。下一次检查就会无法完成走步骤6的逻辑。

源码分析：
class Watchdog extends Thread
    关键属性：
    ArrayList<HandlerChecker> mHandlerCheckers //HandlerChecker列表
    HandlerChecker mMonitorChecker; //在构造函数里边添加到HandlerChecker列表，这个Checker专门用来监听线程死锁

    //检查状态
    static final int COMPLETED = 0;   //检查完成
    static final int WAITING = 1;     //检查未完成，等待中（<30s）
    static final int WAITED_HALF = 2; //检查未完成，等待中（30s<time<60s）
    static final int OVERDUE = 3;     //检查未完成，不能忍了要炸了（>60s）

    关键方法：
    addThread(Handler thread) //根据Handler创建一个HandlerChecker，并且添加到mHandlerCheckers。
    addMonitor(Monitor monitor) //将Monitor添加到mMonitorChecker里边。
    run()方法：
        1、每30s执行发起一次检查，遍历HandlerChecker列表对每个HandlerChecker发起检查（HandlerChecker#scheduleCheckLocked）。
        2、然后检查所有HandlerChecker的状态（HandlerChecker#getCompletionStateLocked），看看是不是有处于检查未完成的HandlerChecker
        3、如果有未完成的HandlerChecker，根据状态做出对应的操作
            WAITED_HALF：dump这个HandlerChecker对应线程的堆栈日志
            OVERDUE：找出出问题的HandlerChecker，dump堆栈日志、eventlog、dropbox log。最后重启SystemServer。

     
class HandlerChecker implements Runnable
    Handler mHandler; //线程的Handler
    ArrayList<Monitor> mMonitors
    private boolean mCompleted; //检查是否已经完成，构造函数中会设为true
    private long mStartTime; //检查开始时间
    private Monitor mCurrentMonitor; //当前执行的Monitor（如果出现死锁，该属性就会有值）

    scheduleCheckLocked方法解析：发起检查
        public void scheduleCheckLocked() {
            //没有锁需要监听 同时 消息队列没有消息在休眠中，无需做检查
            if (mMonitors.size() == 0 && mHandler.getLooper().getQueue().isPolling()) {
                mCompleted = true;
                return;
            }
            //上一个检查还没结束
            if (!mCompleted) {
                return;
            }

            mCompleted = false;
            mCurrentMonitor = null;
            mStartTime = SystemClock.uptimeMillis(); //记录一下检查开始时间
            mHandler.postAtFrontOfQueue(this); //往消息队列发送消息（插入消息队列头部）
        }

    run方法解析：因为postRunnable，所有如果Handler执行消息会走到这里
    public void run() {
            final int size = mMonitors.size();
            for (int i = 0 ; i < size ; i++) { //遍历所有Monitor
                synchronized (Watchdog.this) {
                    //记录当前的Monitor
                    //如果出现阻塞超时，就会通过mCurrentMonitor，打印其实现类。从而知道哪个锁出现死锁

                    mCurrentMonitor = mMonitors.get(i); 
                }
                //Monitor接口的实现方法中，一般就是获取锁操作。如果一直获取不到锁，就会一直卡着（出现死锁）
                mCurrentMonitor.monitor();
            }

            synchronized (Watchdog.this) {
                mCompleted = true;
                mCurrentMonitor = null;
            }
        }


    getCompletionStateLocked方法解析：获取检查状态
        public int getCompletionStateLocked() {
            if (mCompleted) {
                return COMPLETED;
            } else { //没有检查完成
                long latency = SystemClock.uptimeMillis() - mStartTime;
                //mWaitMax 最大等待时长（默认60s）
                if (latency < mWaitMax/2) { //检查超时，但在容忍范围内
                    return WAITING;
                } else if (latency < mWaitMax) { //检查超时，已经超过一半的容忍范围了
                    return WAITED_HALF;
                }
            }
            return OVERDUE; //检查超时，已经无法容忍了
        }


interface Monitor {
    void monitor();
}


AMS中的使用
AMS构造函数中：
    Watchdog.getInstance().addMonitor(this);（AMS实现了Monitor接口）
    Watchdog.getInstance().addThread(mHandler);
AMS#monitor()：
    public void monitor() {
        synchronized (this) { } //获取一下AMS对象锁，看看能不能获取到
    }
```
