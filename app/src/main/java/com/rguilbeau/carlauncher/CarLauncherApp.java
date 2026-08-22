package com.rguilbeau.carlauncher;

import android.app.Application;

import com.rguilbeau.carlauncher.manager.UncaughtExceptionLoggerManager;

public class CarLauncherApp extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        UncaughtExceptionLoggerManager.init(this);
    }
}