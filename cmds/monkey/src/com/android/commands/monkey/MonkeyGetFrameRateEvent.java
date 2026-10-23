/*
 * Copyright (C) 2011 The Android Open Source Project
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
import android.util.Log;
import android.view.IWindowManager;

import java.io.BufferedReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Events for running the shell command to capture frame rate.
 */
public class MonkeyGetFrameRateEvent extends MonkeyEvent {

    private static final String TAG = "MonkeyGetFrameRateEvent";
    private static final String LOG_FILE = "/sdcard/avgFrameRateOut.txt";

    private static final Pattern NO_OF_FRAMES_PATTERN =
            Pattern.compile(".*\\(([a-f[A-F][0-9]].*?)\\s.*\\)");

    private static float mDuration;
    private static int mStartFrameNo;
    private static int mEndFrameNo;
    private static long mStartTime;
    private static long mEndTime;
    private static String mTestCaseName = null;

    private String mStatus;
    private String GET_FRAMERATE_CMD = "service call SurfaceFlinger 1013";

    public MonkeyGetFrameRateEvent(String status) {
        super(MonkeyEvent.EVENT_TYPE_ACTIVITY);
        mStatus = status;
    }

    public MonkeyGetFrameRateEvent(String status, String testCaseName) {
        super(MonkeyEvent.EVENT_TYPE_ACTIVITY);
        mStatus = status;
        mTestCaseName = testCaseName;
    }

    private float getAverageFrameRate(int totalNumberOfFrame, float duration) {
        float avgFrameRate = 0;
        if (duration > 0) {
            avgFrameRate = (totalNumberOfFrame / duration);
        }
        return avgFrameRate;
    }

    /**
     * Parse the number of frames from the given line.
     */
    private String getNumberOfFrames(String input) {
        Matcher m = NO_OF_FRAMES_PATTERN.matcher(input);
        if (m.matches()) {
            return m.group(1);
        }
        return null;
    }

    private void writeAverageFrameRate() {
        FileWriter writer = null;
        try {
            writer = new FileWriter(LOG_FILE, true);
            writer.write(String.format("%s:%.2f\n", mTestCaseName,
                    getAverageFrameRate(mEndFrameNo - mStartFrameNo, mDuration)));
        } catch (IOException e) {
            Log.w(TAG, "Can't write sdcard log file", e);
        } finally {
            if (writer != null) {
                try {
                    writer.close();
                } catch (IOException e) {
                    Log.e(TAG, "IOException " + e.toString());
                }
            }
        }
    }

    @Override
    public int injectEvent(IWindowManager iwm, IActivityManager iam, int verbose) {
        Process process = null;
        BufferedReader reader = null;
        try {
            process = Runtime.getRuntime().exec(GET_FRAMERATE_CMD);
            int status = process.waitFor();
            if (status != 0) {
                Logger.err.println(String.format("// Shell command %s status was %s",
                        GET_FRAMERATE_CMD, status));
            }
            reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String line = reader.readLine();
            if (line != null) {
                if (mStatus == "start") {
                    mStartFrameNo = Integer.parseInt(getNumberOfFrames(line), 16);
                    mStartTime = System.currentTimeMillis();
                } else if (mStatus == "end") {
                    mEndFrameNo = Integer.parseInt(getNumberOfFrames(line), 16);
                    mEndTime = System.currentTimeMillis();
                    mDuration = (float) ((mEndTime - mStartTime) / 1000.0d);
                    writeAverageFrameRate();
                }
            }
        } catch (Exception e) {
            Logger.err.println("// Exception from " + GET_FRAMERATE_CMD + ":");
            Logger.err.println(e.toString());
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (IOException e) {
                    Logger.err.println(e.toString());
                }
            }
            if (process != null) {
                process.destroy();
            }
        }
        return MonkeyEvent.INJECT_SUCCESS;
    }
}
