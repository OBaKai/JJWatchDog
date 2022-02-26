package com.jj.watchdog;

import androidx.appcompat.app.AppCompatActivity;

import android.os.Build;
import android.os.Bundle;
import android.os.Debug;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Log;
import android.view.View;

import jj.watchdog.JJWatchDog;

public class MainActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

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
    }



    public void testThread(View view) {
        Log.e("WatchDog_Log", "testThread");
        HandlerThread handlerThread = new HandlerThread("abc");
        handlerThread.start();

        Handler handler = new Handler(handlerThread.getLooper());
        handler.post(() -> {
            try {
                Thread.sleep(8888);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        });

        JJWatchDog.get().addThread(handler);
    }

    private final Object lock = new Object();

    public void testMonitor(View view) {
        Log.e("WatchDog_Log", "testMonitor");
        JJWatchDog.get().addMonitor(() -> {
            synchronized (lock){ //这里会一直拿不到

            }
        });
        Thread t2 = new Thread(() -> {
            synchronized (lock){ //死锁了
                Log.e("WatchDog_Log", "testMonitor t2 get lock");
            }
        });

        Thread t1= new Thread(() -> {
            synchronized (lock){
                Log.e("WatchDog_Log", "testMonitor t1 get lock");
                t2.start();
                try {
                    t2.join();
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        });

        t1.start();
    }

    public void testMainThread(View view) {
        Log.e("WatchDog_Log", "testMainThread");
        new Handler(Looper.getMainLooper())
                .post(() -> {
                    try {
                        Thread.sleep(8888);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                });
    }
}