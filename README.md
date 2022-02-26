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

