/*
 * Copyright (C) 2007 The Android Open Source Project
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
import android.content.ContentValues;
import android.os.Build;
import android.util.Log;
import android.view.IWindowManager;

import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;

/**
 * monkey power event
 */
public class MonkeyPowerEvent extends MonkeyEvent {

    private static final String TAG = "PowerTester";
    private static final String LOG_FILE = "/sdcard/autotester.log";
    private static final long USB_DELAY_TIME = 10000;

    private static final String TEST_STARTED = "AUTOTEST_TEST_BEGIN";
    private static final String TEST_ENDED = "AUTOTEST_TEST_SUCCESS";
    private static final String TEST_IDLE_ENDED = "AUTOTEST_IDLE_SUCCESS";
    private static final String TEST_SEQ_BEGIN = "AUTOTEST_SEQUENCE_BEGIN";
    private static final String TEST_DELAY_STARTED = "AUTOTEST_TEST_BEGIN_DELAY";

    private static ArrayList<ContentValues> mLogEvents = new ArrayList<ContentValues>();
    private static long mTestStartTime;

    private String mPowerLogTag;
    private String mTestResult;

    public MonkeyPowerEvent() {
        super(MonkeyEvent.EVENT_TYPE_ACTIVITY);
        mPowerLogTag = null;
        mTestResult = null;
    }

    public MonkeyPowerEvent(String powerLogTag) {
        super(MonkeyEvent.EVENT_TYPE_ACTIVITY);
        mPowerLogTag = powerLogTag;
        mTestResult = null;
    }

    public MonkeyPowerEvent(String powerLogTag, String testResult) {
        super(MonkeyEvent.EVENT_TYPE_ACTIVITY);
        mPowerLogTag = powerLogTag;
        mTestResult = testResult;
    }

    private void bufferLogEvent(String tag, String value) {
        long date;
        date = System.currentTimeMillis();

        if (tag.compareTo(TEST_STARTED) == 0) {
            mTestStartTime = date;
        } else if (tag.compareTo(TEST_IDLE_ENDED) == 0) {
            date = Long.parseLong(value) + mTestStartTime;
            tag = TEST_ENDED;
        } else if (tag.compareTo(TEST_DELAY_STARTED) == 0) {
            mTestStartTime = date + USB_DELAY_TIME;
            date = mTestStartTime;
            tag = TEST_STARTED;
        }

        ContentValues event = new ContentValues();
        event.put("date", date);
        event.put("tag", tag);
        if (value != null) {
            event.put("value", value);
        }
        mLogEvents.add(event);
    }

    private void writeLogEvents() {
        ContentValues[] events = mLogEvents.toArray(new ContentValues[0]);
        mLogEvents.clear();

        StringBuffer buffer = new StringBuffer();
        for (ContentValues event : events) {
            buffer.append(MonkeyUtils.toCalendarTime(event.getAsLong("date")));
            buffer.append(event.getAsString("tag"));
            if (event.containsKey("value")) {
                String value = event.getAsString("value");
                buffer.append(" ");
                buffer.append(value.replace('\n', '/'));
            }
            buffer.append("\n");
        }

        FileWriter writer = null;
        try {
            writer = new FileWriter(LOG_FILE, true);
            writer.write(buffer.toString());
        } catch (IOException e) {
            Log.w(TAG, "Can't write sdcard log file", e);
        } finally {
            if (writer != null) {
                try {
                    writer.close();
                } catch (IOException e) {
                    // ignore
                }
            }
        }
    }

    @Override
    public int injectEvent(IWindowManager iwm, IActivityManager iam, int verbose) {
        if (mPowerLogTag == null) {
            writeLogEvents();
            return MonkeyEvent.INJECT_SUCCESS;
        }

        if (mPowerLogTag.compareTo(TEST_SEQ_BEGIN) == 0) {
            bufferLogEvent(mPowerLogTag, Build.FINGERPRINT);
            return MonkeyEvent.INJECT_SUCCESS;
        } else if (mTestResult != null) {
            bufferLogEvent(mPowerLogTag, mTestResult);
            return MonkeyEvent.INJECT_SUCCESS;
        }
        return MonkeyEvent.INJECT_SUCCESS;
    }
}
