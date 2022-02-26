package jj.watchdog;

import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.MessageQueue;
import android.os.SystemClock;
import android.util.Log;
import android.util.Printer;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * author: JJLeong
 * detail: JJ的看门狗 - 监控线程任务 & 监控死锁
 * 灵感来源自 SystemServer 里边的 WatchDog
 *
 * 监控线程任务：定时发送消息检查是否有耗时任务，超时会报警。
 *  主线程监控：可以用来监控ANR但是并不一定准确。不过可以用来做个参考，因为主线程中有耗时任务也是很危险的。
 * 监控死锁：专门有个线程检查锁，只要在Monitor接口的实现里边获取一下锁即可，超时会报警。
 */
public class JJWatchDog extends Thread{
    private static final String TAG = "WatchDog_Log";

    private static final long DEFAULT_TIMEOUT = 5 * 1000;

    private boolean isWorking = false;

    private final ArrayList<HandlerChecker> mHandlerCheckers = new ArrayList<>(2);

    /**
     * 监控线程死锁的HandlerChecker
     */
    private HandlerChecker mMonitorChecker;
    /**
     * 监控死锁的线程
     */
    private MonitorHandler mMonitorHandler;

    private WatchDogListener mListener;
    private boolean isPrintLog;
    private long checkTimeInterval = DEFAULT_TIMEOUT;
    private boolean isCloseDefaultMessageLogging = false;
    private boolean isPostAtFrontOfQueue = false;

    private boolean isCloseDefaultMainThreadCheck = false;
    private long main_waitMaxMillis = DEFAULT_TIMEOUT;
    private Printer main_Printer;

    private static JJWatchDog sWatchdog;
    public static JJWatchDog get() {
        if (sWatchdog == null) { sWatchdog = new JJWatchDog(); }
        return sWatchdog;
    }
    private JJWatchDog() { super("JJWatchDog_Thread"); }

    //region  ===== 建造者方式的方法 =====

    /**
     * 线程耗时超标 或 死锁 会走靠这个监控器回调
     * @param listener 监听器
     */
    public JJWatchDog setWatchDogListener(WatchDogListener listener){
        mListener = listener;
        return this;
    }

    /**
     * 关闭内部对所有Handler默认实现的Looper#setMessageLogging
     * 默认内部会接管Handler里边的Looper#setMessageLogging，用于分析每个Msg的执行时长让监控器更加精准报警。
     */
    public JJWatchDog closeDefaultMessageLogging(){
        isCloseDefaultMessageLogging = true;
        return this;
    }

    /**
     * 关闭内部默认实现的主线程监控器
     */
    public JJWatchDog closeDefaultMainThreadCheck(){
        isCloseDefaultMainThreadCheck = true;
        return this;
    }

    /**
     * 给所有Handler发高优先的Msg
     * true: Handler#postAtFrontOfQueue；false：Handler#post
     */
    public JJWatchDog openPostAtFrontOfQueue(){
        isPostAtFrontOfQueue = true;
        return this;
    }

    /**
     * 自定义主线程Checker配置
     * @param waitMaxMillis - 最大等待时长，超过这个时长才回调
     */
    public JJWatchDog customMainThreadChecker(long waitMaxMillis){
        customMainThreadChecker(waitMaxMillis, null);
        return this;
    }

    public JJWatchDog customMainThreadChecker(long waitMaxMillis, Printer printer){
        main_waitMaxMillis = waitMaxMillis;
        main_Printer = printer;
        return this;
    }

    /**
     * 检查间隔（每隔这个时间间隔检查一次）
     * @param millisecond 单位毫秒
     */
    public JJWatchDog setCheckTimeInterval(long millisecond){
        checkTimeInterval = millisecond;
        return this;
    }

    /**
     * 是否打印内部日志（默认是关闭的）
     */
    public JJWatchDog isPrintLog(boolean isPrint){
        isPrintLog = isPrint;
        return this;
    }

    /**
     * 启动线程，开始轮询检查
     */
    public void loop(){
        if (!isCloseDefaultMainThreadCheck){
            mHandlerCheckers.add(new HandlerChecker(new Handler(Looper.getMainLooper()),
                    "MainThread",
                    main_waitMaxMillis,
                    isPostAtFrontOfQueue,
                    isCloseDefaultMessageLogging,
                    main_Printer));
        }

        if (!mHandlerCheckers.isEmpty()){
            start();
        }
    }
    //endregion

    @Override
    public void run() {
        while (isWorking) {
            List<HandlerChecker> blockedCheckers = null;
            synchronized (this) {
                long timeout = checkTimeInterval;
                for (int i = 0; i < mHandlerCheckers.size(); i++) {
                    HandlerChecker hc = mHandlerCheckers.get(i);
                    hc.scheduleCheck();
                }

                long start = SystemClock.uptimeMillis();
                while (timeout > 0) {
                    try {
                        log(Log.DEBUG, "wait " + timeout);
                        wait(timeout);
                    } catch (InterruptedException e) {
                        log(Log.ERROR, "wait fail, err=" + e.getMessage());
                    }
                    timeout = checkTimeInterval - (SystemClock.uptimeMillis() - start);
                }

                if (!evaluateCheckerCompletion()) {
                    blockedCheckers = getBlockedCheckers();
                }
            }

            //发现有阻塞的Checker了
            if (blockedCheckers != null){
                for (HandlerChecker checker : blockedCheckers){
                    log(Log.WARN, "found blockedChecker：" + checker.getName());
                    if (mListener != null){
                        mListener.onThreadBlocked(checker.getName(),
                                new WatchDogThrowable(checker.describeBlockedState(),
                                        checker.getThread().getStackTrace()));
                    }
                }
            }
        }
    }

    private void initMonitorChecker(){
        if (mMonitorChecker == null){
            mMonitorHandler = new MonitorHandler();
            mMonitorHandler.start();

            mMonitorChecker = new HandlerChecker(mMonitorHandler.getHandler(),
                    mMonitorHandler.getHandler().getLooper().getThread().getName(),
                    checkTimeInterval,
                    isPostAtFrontOfQueue,
                    isCloseDefaultMessageLogging,
                    null);
            mHandlerCheckers.add(mMonitorChecker);

            if (!isWorking){
                start();
            }
        }
    }

    /**
     * 计算Checker们是否已经完成了，如果有任何一个没完成就返回
     */
    private boolean evaluateCheckerCompletion() {
        for (int i = 0; i < mHandlerCheckers.size(); i++) {
            HandlerChecker hc = mHandlerCheckers.get(i);
            if (!hc.isCompletion()){
                return false;
            }
        }
        return true;
    }

    private ArrayList<HandlerChecker> getBlockedCheckers() {
        ArrayList<HandlerChecker> checkers = new ArrayList<>();
        for (int i=0; i<mHandlerCheckers.size(); i++) {
            HandlerChecker hc = mHandlerCheckers.get(i);
            if (hc.isOverdueLocked()) {
                checkers.add(hc);
            }
        }
        return checkers;
    }

    /**
     * 添加锁监控
     */
    public synchronized void addMonitor(Monitor monitor) {
        initMonitorChecker();
        mMonitorChecker.addMonitor(monitor);
    }

    /**
     * 移除锁监控
     */
    public synchronized void removeMonitor(Monitor monitor){
        if (mMonitorChecker != null){
            mMonitorChecker.removeMonitor(monitor);
        }
    }

    public void addThread(Handler thread) {
        addThread(thread, checkTimeInterval, true);
    }

    /**
     * 添加子线程监控
     * @param thread - Handler
     * @param timeoutMillis - 超时时长
     * @param isPostAtFrontOfQueue - 是否高优先监控
     */
    public synchronized void addThread(Handler thread, long timeoutMillis, boolean isPostAtFrontOfQueue) {
        if (thread == null){
            throw new IllegalArgumentException("thread is null.");
        }

        if (thread.getLooper() == Looper.getMainLooper()){
            throw new IllegalArgumentException("can't add mainThread.");
        }

        if (!mHandlerCheckers.isEmpty()){
            for (HandlerChecker h : mHandlerCheckers){
                if (h.isSelf(thread)){
                    throw new IllegalArgumentException("can't add this thread again.");
                }
            }
        }

        HandlerChecker hc = new HandlerChecker(thread,
                thread.getLooper().getThread().getName(),
                timeoutMillis,
                isPostAtFrontOfQueue);
        log(Log.INFO, "addThread " + hc.getName());
        mHandlerCheckers.add(hc);

        if (!isWorking){
            start();
        }
    }

    /**
     * 移除子线程监控
     */
    public synchronized void removeThread(Handler thread){
        Iterator<HandlerChecker> it = mHandlerCheckers.iterator();
        while(it.hasNext()){
            HandlerChecker hc = it.next();
            if (hc.isSelf(thread)){
                log(Log.INFO, "removeThread " + hc.getName());
                hc.release();
                it.remove();
            }
        }

        //没有Checker，就把线程暂停了吧
        if (mHandlerCheckers.isEmpty()){
            isWorking = false;
        }
    }

    /**
     * 启动线程
     */
    @Override
    public synchronized void start() {
        if (isWorking) return;

        log(Log.INFO, "WatchDog start.");
        isWorking = true;
        super.start();
    }

    public synchronized void pause(){
        log(Log.INFO, "WatchDog pause.");
        isWorking = false;
    }

    public synchronized void release(){
        log(Log.INFO, "WatchDog release.");
        isWorking = false;

        for (HandlerChecker hc : mHandlerCheckers){
            hc.release();
        }
        mHandlerCheckers.clear();

        if (mMonitorChecker != null){
            mMonitorChecker = null;

            mMonitorHandler.quit();
            mMonitorHandler = null;
        }
    }

    private void log(int logPriority, String msg){
        if (isPrintLog){
            Log.println(logPriority, TAG, msg);
        }
    }

    public final class HandlerChecker implements Runnable {
        private final Handler mHandler;
        private final String mName;
        private final long mWaitMax;
        private final ArrayList<Monitor> mMonitors = new ArrayList<>();
        private boolean mCompleted;
        private Monitor mCurrentMonitor;
        private long mStartTime;
        private final boolean isPostAtFront;

        private long mMsgDispatchTime; //msg开始执行时间，该属性会在handler线程、watchdog线程用到
        private String mMsgDispatchInfo; //msg信息，该属性会在handler线程、watchdog线程用到

        HandlerChecker(Handler handler, String name, long waitMaxMillis) {
            this(handler, name, waitMaxMillis, true, false,null);
        }

        HandlerChecker(Handler handler, String name, long waitMaxMillis, boolean isPostAtFront) {
            this(handler, name, waitMaxMillis, isPostAtFront, false,null);
        }

        HandlerChecker(Handler handler, String name, long waitMaxMillis, boolean isPostAtFront, Printer printer) {
            this(handler, name, waitMaxMillis, isPostAtFront, false, printer);
        }

        HandlerChecker(Handler handler, String name, long waitMaxMillis, boolean isAtFront, boolean isCloseMessageLogging, Printer printer) {
            mHandler = handler;
            mName = name;
            mWaitMax = waitMaxMillis;
            isPostAtFront = isAtFront;
            mCompleted = true;

            if (!isCloseMessageLogging){
                mHandler.getLooper().setMessageLogging(log -> {
                    evaluateMsgTimeFromMessageLogging(log);
                    if (printer != null){
                        printer.println(log);
                    }
                });
            }
        }

        /**
         * 通过Looper日志计算msg执行的耗时
         * 这方法执行在handler线程
         *
         * msg逾期的情况：
         * 在evaluateMsgTimeFromMessageLogging里边发现msg逾期了，但是watchdog线程还在休眠。
         * 需要先记录逾期的msg信息，等watchdog执行的时候才报警。
         */
        private void evaluateMsgTimeFromMessageLogging(String log){
            log(Log.VERBOSE, mName + " log: " + log);
            //log format
            //">>>>> Dispatching to " + msg.target + " " + msg.callback + ": " + msg.what
            //"<<<<< Finished to " + msg.target + " " + msg.callback
            //log example
            //>>>>> Dispatching to Handler (android.view.Choreographer$FrameHandler) {b1b1ea1} android.view.Choreographer$FrameDisplayEventReceiver@f6748c6: 0
            //<<<<< Finished to Handler (android.view.Choreographer$FrameHandler) {b1b1ea1} android.view.Choreographer$FrameDisplayEventReceiver@f6748c6

            //每个线程的消息队列都是一条条消息去执行，所以日志肯定是成对出现的。
            //所有我觉得不用对每条消息都校验 msg.target + msg.callback 来确保是不是同一条消息的。
            if (log.startsWith(">>>>> Dispatching to ")){
                mMsgDispatchTime = SystemClock.uptimeMillis();
                mMsgDispatchInfo = log.replace(">>>>> Dispatching to ", "");
            }else {
                long time = mMsgDispatchTime > 0
                        ? SystemClock.uptimeMillis() - mMsgDispatchTime : 0;
                if (time < mWaitMax){
                    mMsgDispatchTime = 0;
                    mMsgDispatchInfo = null;
                }else { //发现该消息逾期了
                    log(Log.WARN, "found overdue message: " + mMsgDispatchInfo);
                    if (mListener != null){
                        mListener.onHandleMessageOverdue(mMsgDispatchInfo);
                    }
                }
            }
        }

        @Override
        public void run() {
            log(Log.DEBUG, mName + " -> run interval=" + (SystemClock.uptimeMillis() - mStartTime));
            final int size = mMonitors.size();
            for (int i = 0 ; i < size ; i++) {
                synchronized (JJWatchDog.this) {
                    mCurrentMonitor = mMonitors.get(i);
                }
                mCurrentMonitor.monitor();
            }

            synchronized (JJWatchDog.this) {
                mCompleted = true;
                mCurrentMonitor = null;
            }
        }

        /**
         * 发起检查
         */
        public void scheduleCheck() {
            if (mMonitors.size() == 0 && isPolling(mHandler)) {
                log(Log.DEBUG, mName + " -> mMonitors is empty or MessageQueue is polling.");
                mCompleted = true;
                return;
            }

            if (!mCompleted) {
                log(Log.DEBUG, mName + " -> waiting.");
                return;
            }

            mCompleted = false;
            mCurrentMonitor = null;
            mStartTime = SystemClock.uptimeMillis();
            if (isPostAtFront){
                mHandler.postAtFrontOfQueue(this);
            }else {
                mHandler.post(this);
            }
            log(Log.DEBUG, mName + " -> scheduleCheck.");
        }

        /**
         * 取消检查
         */
        private void unscheduleCheck(){
            if (mCompleted){
                return;
            }
            mHandler.removeCallbacks(this);
        }

        /**
         * 判断当前Handler是否空闲
         */
        private boolean isPolling(Handler handler){
            boolean isPolling = false;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) { //6.0以上直接反射MessageQueue#isPolling
                try {
                    Method method = MessageQueue.class.getDeclaredMethod("isPolling", null);
                    method.setAccessible(true);
                    isPolling = (boolean) method.invoke(handler.getLooper().getQueue(), null);
                } catch (Exception e) {
                    log(Log.ERROR, "M+ invoke isPolling fail. err=" + e.getMessage());
                }
            }else { //6.0以下先反射拿到MessageQueue，再反射MessageQueue#isPolling
                try {
                    Field field = Handler.class.getDeclaredField("mQueue");
                    field.setAccessible(true);
                    MessageQueue mq = (MessageQueue) field.get(handler);

                    Method method = MessageQueue.class.getDeclaredMethod("isPolling", null);
                    method.setAccessible(true);
                    isPolling = (boolean) method.invoke(mq, null);
                } catch (Exception e) {
                    log(Log.ERROR, "M- invoke isPolling fail. err=" + e.getMessage());
                }
            }
            return isPolling;
        }

        public void addMonitor(Monitor monitor) {
            if (!mMonitors.contains(monitor)) mMonitors.add(monitor);
        }

        public void removeMonitor(Monitor monitor){
            mMonitors.remove(monitor);
        }

        /**
         * 是否逾期了
         */
        public boolean isOverdueLocked() {
            return (!mCompleted) && (SystemClock.uptimeMillis() > mStartTime + mWaitMax);
        }

        public boolean isCompletion() {
            return mCompleted;
        }

        public Thread getThread() {
            return mHandler.getLooper().getThread();
        }

        public String getName() {
            return mName;
        }

        public String describeBlockedState() {
            if (mCurrentMonitor == null) {
                return "Blocked in handler on " + mName + " (" + getThread().getName() + ")";
            } else {
                return "Blocked in monitor " + mCurrentMonitor.getClass().getName() + " on " + mName + " (" + getThread().getName() + ")";
            }
        }

        public boolean isSelf(Handler handler){
            return mHandler == handler;
        }

        public void release(){
            unscheduleCheck();
            mMonitors.clear();
        }
    }

    public interface Monitor {
        void monitor();
    }

    public interface WatchDogListener{
        /**
         * 检测到有线程阻塞
         * 在watchdog线程执行回调
         */
        void onThreadBlocked(String threadName, WatchDogThrowable throwable);

        /**
         * 检测到有handle消息逾期了（超出了最大监控时长 HandlerChecker#mWaitMax）
         * 在handle对应的线程执行回调
         */
        default void onHandleMessageOverdue(String messageInfo){};
    }

    private static final class MonitorHandler extends HandlerThread{

        MonitorHandler() {
            super("Monitor_HandlerThread");
        }

        Handler getHandler(){
            return new Handler(getLooper());
        }
    }

    public static final class WatchDogThrowable extends Throwable{
        WatchDogThrowable(String msg, StackTraceElement[] stackTrace){
            super(msg);
            setStackTrace(stackTrace);
        }
    }
}
