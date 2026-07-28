package com.nukacast.app;

import android.app.Application;
import android.content.Context;

import androidx.test.runner.AndroidJUnitRunner;

/** Keeps device tests isolated from receiver services and native startup. */
public final class NukaCastTestRunner extends AndroidJUnitRunner {
    @Override
    public Application newApplication(ClassLoader classLoader, String className, Context context)
            throws InstantiationException, IllegalAccessException, ClassNotFoundException {
        return super.newApplication(classLoader, Application.class.getName(), context);
    }
}
