package com.jj.watchdog;

import androidx.appcompat.app.AppCompatActivity;

import android.os.Build;
import android.os.Bundle;
import android.os.Debug;
import android.os.Handler;
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
                .customMainThreadChecker(4 * 1000)
                .isPrintLog(BuildConfig.DEBUG)
                .setWatchDogListener((threadName, throwable) -> {
                    Log.e("WatchDog_Log", "onHandleBlocked " + threadName);
                    throwable.printStackTrace();
                })
                .loop();
    }

    public void testThread(View view) {
        Log.e("WatchDog_Log", "testThread");
        new Handler(Looper.getMainLooper())
                .post(() -> {
                    try {
                        Thread.sleep(5555);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                });
    }

    public void testMonitor(View view) {
        Log.e("WatchDog_Log", "testMonitor");
    }
}