/*
 * Copyright (C) 2026 The GalaxyCameraRevived Project
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
 * limitations under the License.
 */

package com.android.systemui.power;

import android.app.AlarmManager;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Slog;

import com.android.internal.statusbar.IStatusBarService;
import com.android.systemui.broadcast.BroadcastDispatcher;
import com.android.systemui.keyguard.WakefulnessLifecycle;
import com.android.systemui.res.R;

/**
 * Powers the device off once it has been asleep and unplugged for the user's auto power off
 * time, like a camera's auto power off. Waking the screen or plugging in a charger restarts the
 * count. Only devices that set config_autoPowerOffTimeout use it.
 */
final class AutoPowerOff {
    private static final String TAG = "AutoPowerOff";

    /** The user's time in ms; 0 means never. Settings > Battery writes it. */
    static final String SETTING = "auto_power_off_timeout";

    private final Context mContext;
    private final Handler mHandler;
    private final BroadcastDispatcher mBroadcastDispatcher;
    private final WakefulnessLifecycle mWakefulnessLifecycle;
    private final AlarmManager mAlarmManager;

    private long mTimeout;
    private boolean mAsleep;
    private boolean mPlugged;
    private boolean mScheduled;

    private final AlarmManager.OnAlarmListener mAlarmListener = this::onAlarm;

    private final WakefulnessLifecycle.Observer mWakefulnessObserver =
            new WakefulnessLifecycle.Observer() {
                @Override
                public void onStartedWakingUp() {
                    mAsleep = false;
                    update();
                }

                @Override
                public void onFinishedGoingToSleep() {
                    mAsleep = true;
                    update();
                }
            };

    private final BroadcastReceiver mBatteryReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            boolean plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0;
            if (plugged != mPlugged) {
                mPlugged = plugged;
                update();
            }
        }
    };

    AutoPowerOff(Context context, Handler handler, BroadcastDispatcher broadcastDispatcher,
            WakefulnessLifecycle wakefulnessLifecycle) {
        mContext = context;
        mHandler = handler;
        mBroadcastDispatcher = broadcastDispatcher;
        mWakefulnessLifecycle = wakefulnessLifecycle;
        mAlarmManager = context.getSystemService(AlarmManager.class);
    }

    void start() {
        int defaultTimeout = mContext.getResources().getInteger(
                R.integer.config_autoPowerOffTimeout);
        if (defaultTimeout < 0) {
            return;
        }
        final ContentResolver resolver = mContext.getContentResolver();
        // Settings shows its entry only once the setting exists
        if (Settings.Global.getString(resolver, SETTING) == null) {
            Settings.Global.putLong(resolver, SETTING, defaultTimeout);
        }
        mTimeout = Settings.Global.getLong(resolver, SETTING, defaultTimeout);
        resolver.registerContentObserver(Settings.Global.getUriFor(SETTING), false,
                new ContentObserver(mHandler) {
                    @Override
                    public void onChange(boolean selfChange) {
                        mTimeout = Settings.Global.getLong(resolver, SETTING, defaultTimeout);
                        // A new time counts from now
                        cancel();
                        update();
                    }
                });

        mAsleep = !mContext.getSystemService(PowerManager.class).isInteractive();
        Intent battery = mContext.registerReceiver(null,
                new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        mPlugged = battery != null
                && battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0;
        mBroadcastDispatcher.registerReceiverWithHandler(mBatteryReceiver,
                new IntentFilter(Intent.ACTION_BATTERY_CHANGED), mHandler);
        mWakefulnessLifecycle.addObserver(mWakefulnessObserver);
        update();
    }

    private void update() {
        if (!mAsleep || mPlugged || mTimeout <= 0) {
            cancel();
        } else if (!mScheduled) {
            Slog.i(TAG, "Powering off in " + mTimeout / 60000 + " min unless woken or plugged");
            mAlarmManager.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    SystemClock.elapsedRealtime() + mTimeout, TAG, mHandler::post, null,
                    mAlarmListener);
            mScheduled = true;
        }
    }

    private void cancel() {
        if (mScheduled) {
            Slog.i(TAG, "Cancelled (asleep " + mAsleep + ", plugged " + mPlugged + ")");
            mAlarmManager.cancel(mAlarmListener);
            mScheduled = false;
        }
    }

    private void onAlarm() {
        mScheduled = false;
        if (!mAsleep || mPlugged) {
            return;
        }
        Slog.i(TAG, "Asleep and unplugged for " + mTimeout / 60000 + " min, powering off");
        try {
            IStatusBarService.Stub.asInterface(
                    ServiceManager.getService(Context.STATUS_BAR_SERVICE)).shutdown();
        } catch (RemoteException e) {
            Slog.e(TAG, "Power off failed", e);
        }
    }
}
