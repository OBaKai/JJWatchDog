package jj.watchdog;

import android.os.SystemClock;
import android.util.Log;

import java.lang.ref.WeakReference;
import java.util.ArrayList;

/**
 * author: JJLeong
 * detail: GC看门狗 - 每次GC都会触发Runnable
 */
public class GcWatchDog {
    private static WeakReference<GcWatcher> gcWatchDogReference = new WeakReference<>(new GcWatcher());
    private static final ArrayList<Runnable> watchDogList = new ArrayList<>();
    private static Runnable[] mTmpWatchDog = new Runnable[1];

    private static long mLastGcTime;

    private static final class GcWatcher {
        @Override
        protected void finalize() throws Throwable {
            //Log.e("JJ", "finalize " + (SystemClock.uptimeMillis() - mLastGcTime));
            mLastGcTime = SystemClock.uptimeMillis();
            synchronized (watchDogList) {
                mTmpWatchDog = watchDogList.toArray(mTmpWatchDog);
            }
            for (Runnable runnable : mTmpWatchDog) {
                if (runnable != null) {
                    runnable.run();
                }
            }
            gcWatchDogReference = new WeakReference<>(new GcWatcher());
        }
    }

    public static void addGcWatchDog(Runnable watcher) {
        synchronized (watchDogList) {
            watchDogList.add(watcher);
        }
    }

    public static void removeGcWatchDog(Runnable watcher){
        synchronized (watchDogList) {
            watchDogList.remove(watcher);
        }
    }

    public static long getLastGcTime() {
        return mLastGcTime;
    }
}