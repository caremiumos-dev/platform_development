/*
 * Copyright (C) 2010 The Android Open Source Project
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

package com.android.commands.monkey;

import android.app.IActivityManager;
import android.content.IIntentReceiver;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.NetworkInfo;
import android.os.Bundle;
import android.os.RemoteException;
import android.os.SystemClock;

/**
 * Class for monitoring network stats during a monkey run.
 */
public class MonkeyNetworkMonitor extends IIntentReceiver.Stub {
    private static final boolean LDEBUG = false;
    private final IntentFilter filter = new IntentFilter(
            "android.net.conn.CONNECTIVITY_CHANGE");
    private long mCollectionStartTime;
    private long mEventTime;
    private int mLastNetworkType = -1;
    private long mWifiElapsedTime = 0;
    private long mMobileElapsedTime = 0;
    private long mElapsedTime = 0;

    public void performReceive(Intent intent, int resultCode, String data,
            Bundle extras, boolean ordered, boolean sticky, int sendingUser)
            throws RemoteException {
        NetworkInfo ni = (NetworkInfo) intent.getParcelableExtra("networkInfo");
        updateNetworkStats();
        if (NetworkInfo.State.CONNECTED == ni.getState()) {
            mLastNetworkType = ni.getType();
        } else if (NetworkInfo.State.DISCONNECTED == ni.getState()) {
            mLastNetworkType = -1;
        }
        mEventTime = SystemClock.elapsedRealtime();
    }

    private void updateNetworkStats() {
        long timeNow = SystemClock.elapsedRealtime();
        long delta = timeNow - mEventTime;
        switch (mLastNetworkType) {
            case 0:
                mMobileElapsedTime += delta;
                break;
            case 1:
                mWifiElapsedTime += delta;
                break;
            default:
                break;
        }
        mElapsedTime = timeNow - mCollectionStartTime;
    }

    public void start() {
        mWifiElapsedTime = 0;
        mMobileElapsedTime = 0;
        mElapsedTime = 0;
        mCollectionStartTime = SystemClock.elapsedRealtime();
        mEventTime = mCollectionStartTime;
    }

    public void stop() {
        updateNetworkStats();
    }

    public void register(IActivityManager am) throws RemoteException {
        am.registerReceiver(null, null, this, filter, null, -1, 0);
    }

    public void unregister(IActivityManager am) throws RemoteException {
        am.unregisterReceiver(this);
    }

    public void dump() {
        Logger.out.println("## Network stats: elapsed time=" + mElapsedTime + "ms ("
                + mMobileElapsedTime + "ms mobile, " + mWifiElapsedTime + "ms wifi, "
                + (mElapsedTime - mMobileElapsedTime - mWifiElapsedTime) + "ms not connected)");
    }
}
