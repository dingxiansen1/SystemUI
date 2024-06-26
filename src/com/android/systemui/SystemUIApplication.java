/*
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License
 */

package com.android.systemui;

import android.app.ActivityThread;
import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.res.Configuration;
import android.os.Process;
import android.os.SystemProperties;
import android.os.Trace;
import android.os.UserHandle;
import android.util.Log;
import android.util.TimingsTraceLog;

import com.android.systemui.dagger.ContextComponentHelper;
import com.android.systemui.dagger.SystemUIRootComponent;
import com.android.systemui.dump.DumpManager;
import com.android.systemui.util.NotificationChannels;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;

/**
 * Application class for SystemUI.
 */
public class SystemUIApplication extends Application implements
        SystemUIAppComponentFactory.ContextInitializer {

    public static final String TAG = "SystemUIService";
    private static final boolean DEBUG = false;

    private ContextComponentHelper mComponentHelper;
    private BootCompleteCacheImpl mBootCompleteCache;

    /**
     * Hold a reference on the stuff we start.
     */
    private SystemUI[] mServices;
    private boolean mServicesStarted;
    private SystemUIAppComponentFactory.ContextAvailableCallback mContextAvailableCallback;
    private SystemUIRootComponent mRootComponent;

    public SystemUIApplication() {
        super();
        Log.v(TAG, "SystemUIApplication constructed.");
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Log.v(TAG, "SystemUIApplication created.");
        // This line is used to setup Dagger's dependency injection and should be kept at the
        // top of this method.
        // TimingsTraceLog　是一个用于跟踪代码执行时间的工具类，它可以在traceview中看到。
        TimingsTraceLog log = new TimingsTraceLog("SystemUIBootTiming",
                Trace.TRACE_TAG_APP);
        log.traceBegin("DependencyInjection");
        // 此行用于设置Dagger的依赖注入，并应保持在onrecate方法的顶部。
        mContextAvailableCallback.onContextAvailable(this);
        mRootComponent = SystemUIFactory.getInstance().getRootComponent();
        mComponentHelper = mRootComponent.getContextComponentHelper();
        // BootCompleteCacheImpl 是一个用于缓存 BOOT_COMPLETED 广播的实现类。
        mBootCompleteCache = mRootComponent.provideBootCacheImpl();
        log.traceEnd();

        // Set the application theme that is inherited by all services. Note that setting the
        // application theme in the manifest does only work for activities. Keep this in sync with
        // the theme set there.
        setTheme(R.style.Theme_SystemUI);
        //判断当前进程是否是系统进程。如果是系统进程，那么就注册 BOOT_COMPLETED（开机广播） 广播接收器。
        if (Process.myUserHandle().equals(UserHandle.SYSTEM)) {
            // 创建 BOOT_COMPLETED 广播接收器的意图过滤器。
            IntentFilter bootCompletedFilter = new IntentFilter(Intent.ACTION_BOOT_COMPLETED);
            bootCompletedFilter.setPriority(IntentFilter.SYSTEM_HIGH_PRIORITY);
            // 注册 BOOT_COMPLETED 广播接收器。
            registerReceiver(new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    if (mBootCompleteCache.isBootComplete()) return;

                    if (DEBUG) Log.v(TAG, "BOOT_COMPLETED received");
                    unregisterReceiver(this);
                    mBootCompleteCache.setBootComplete();
                    // 判断SystemUIService是否启动
                    if (mServicesStarted) {
                        final int N = mServices.length;
                        for (int i = 0; i < N; i++) {
                            // 通知SystemUI中各个组件，系统启动完成。
                            mServices[i].onBootCompleted();
                        }
                    }
                }
            }, bootCompletedFilter);
            // Intent.ACTION_LOCALE_CHANGED 是系统语言发生变化时发送的广播。
            IntentFilter localeChangedFilter = new IntentFilter(Intent.ACTION_LOCALE_CHANGED);
            registerReceiver(new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    if (Intent.ACTION_LOCALE_CHANGED.equals(intent.getAction())) {
                        if (!mBootCompleteCache.isBootComplete()) return;
                        // Update names of SystemUi notification channels
                        // 更新SystemUi通知通道的名称
                        NotificationChannels.createAll(context);
                    }
                }
            }, localeChangedFilter);
        } else {
            // We don't need to startServices for sub-process that is doing some tasks.
            // (screenshots, sweetsweetdesserts or tuner ..)
            String processName = ActivityThread.currentProcessName();
            ApplicationInfo info = getApplicationInfo();
            if (processName != null && processName.startsWith(info.processName + ":")) {
                return;
            }
            // For a secondary user, boot-completed will never be called because it has already
            // been broadcasted on startup for the primary SystemUI process.  Instead, for
            // components which require the SystemUI component to be initialized per-user, we
            // start those components now for the current non-system user.
            startSecondaryUserServicesIfNeeded();
        }
    }

    /**
     * Makes sure that all the SystemUI services are running. If they are already running, this is a
     * no-op. This is needed to conditinally start all the services, as we only need to have it in
     * the main process.
     * <p>This method must only be called from the main thread.</p>
     */

    public void startServicesIfNeeded() {
        String[] names = SystemUIFactory.getInstance().getSystemUIServiceComponents(getResources());
        startServicesIfNeeded(/* metricsPrefix= */ "StartServices", names);
    }

    /**
     * Ensures that all the Secondary user SystemUI services are running. If they are already
     * running, this is a no-op. This is needed to conditionally start all the services, as we only
     * need to have it in the main process.
     * <p>This method must only be called from the main thread.</p>
     */
    void startSecondaryUserServicesIfNeeded() {
        String[] names = SystemUIFactory.getInstance().getSystemUIServiceComponentsPerUser(
                getResources());
        startServicesIfNeeded(/* metricsPrefix= */ "StartSecondaryServices", names);
    }

    private void startServicesIfNeeded(String metricsPrefix, String[] services) {
        if (mServicesStarted) {
            return;
        }
        mServices = new SystemUI[services.length];

        if (!mBootCompleteCache.isBootComplete()) {
            // check to see if maybe it was already completed long before we began
            // see ActivityManagerService.finishBooting()
            if ("1".equals(SystemProperties.get("sys.boot_completed"))) {
                mBootCompleteCache.setBootComplete();
                if (DEBUG) {
                    Log.v(TAG, "BOOT_COMPLETED was already sent");
                }
            }
        }

        final DumpManager dumpManager = mRootComponent.createDumpManager();

        Log.v(TAG, "Starting SystemUI services for user " +
                Process.myUserHandle().getIdentifier() + ".");
        TimingsTraceLog log = new TimingsTraceLog("SystemUIBootTiming",
                Trace.TRACE_TAG_APP);
        log.traceBegin(metricsPrefix);
        final int N = services.length;
        for (int i = 0; i < N; i++) {
            String clsName = services[i];
            if (DEBUG) Log.d(TAG, "loading: " + clsName);
            log.traceBegin(metricsPrefix + clsName);
            long ti = System.currentTimeMillis();
            try {
                SystemUI obj = mComponentHelper.resolveSystemUI(clsName);
                if (obj == null) {
                    Constructor constructor = Class.forName(clsName).getConstructor(Context.class);
                    obj = (SystemUI) constructor.newInstance(this);
                }
                mServices[i] = obj;
            } catch (ClassNotFoundException
                    | NoSuchMethodException
                    | IllegalAccessException
                    | InstantiationException
                    | InvocationTargetException ex) {
                throw new RuntimeException(ex);
            }

            if (DEBUG) Log.d(TAG, "running: " + mServices[i]);
            mServices[i].start();
            log.traceEnd();

            // Warn if initialization of component takes too long
            ti = System.currentTimeMillis() - ti;
            if (ti > 1000) {
                Log.w(TAG, "Initialization of " + clsName + " took " + ti + " ms");
            }
            if (mBootCompleteCache.isBootComplete()) {
                mServices[i].onBootCompleted();
            }

            dumpManager.registerDumpable(mServices[i].getClass().getName(), mServices[i]);
        }
        mRootComponent.getInitController().executePostInitTasks();
        log.traceEnd();

        mServicesStarted = true;
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        if (mServicesStarted) {
            mRootComponent.getConfigurationController().onConfigurationChanged(newConfig);
            int len = mServices.length;
            for (int i = 0; i < len; i++) {
                if (mServices[i] != null) {
                    mServices[i].onConfigurationChanged(newConfig);
                }
            }
        }
    }

    public SystemUI[] getServices() {
        return mServices;
    }

    @Override
    public void setContextAvailableCallback(
            SystemUIAppComponentFactory.ContextAvailableCallback callback) {
        mContextAvailableCallback = callback;
    }

}
