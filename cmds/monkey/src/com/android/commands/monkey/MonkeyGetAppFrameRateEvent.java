/*
 * Copyright (C) 2012 The Android Open Source Project
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
import android.os.Environment;
import android.util.Log;
import android.view.IWindowManager;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Events for running the shell command to capture the average app frame rate.
 */
public class MonkeyGetAppFrameRateEvent extends MonkeyEvent {

    private static final String TAG = "MonkeyGetAppFrameRateEvent";

    private static final String LOG_FILE = new File(
            Environment.getExternalStorageDirectory(), "avgAppFrameRateOut.txt")
            .getAbsolutePath();

    private static final Pattern NO_OF_FRAMES_PATTERN =
            Pattern.compile(".* ([0-9]*) frames rendered");

    private static float sDuration;
    private static int sStartFrameNo;
    private static int sEndFrameNo;
    private static long sStartTime;
    private static long sEndTime;
    private static String sActivityName = null;
    private static String sTestCaseName = null;

    private String mStatus;
    private String GET_APP_FRAMERATE_TMPL = "dumpsys gfxinfo %s";

    public MonkeyGetAppFrameRateEvent(String status) {
        super(MonkeyEvent.EVENT_TYPE_ACTIVITY);
        mStatus = status;
    }

    public MonkeyGetAppFrameRateEvent(String status, String activityName) {
        super(MonkeyEvent.EVENT_TYPE_ACTIVITY);
        mStatus = status;
        sActivityName = activityName;
    }

    public MonkeyGetAppFrameRateEvent(String status, String activityName, String testCaseName) {
        super(MonkeyEvent.EVENT_TYPE_ACTIVITY);
        mStatus = status;
        sActivityName = activityName;
        sTestCaseName = testCaseName;
    }

    private float getAverageFrameRate(int totalNumberOfFrame, float duration) {
        float avgFrameRate = 0;
        if (duration > 0) {
            avgFrameRate = (totalNumberOfFrame / duration);
        }
        return avgFrameRate;
    }

    /**
     * Parse the number of frames from the buffered reader, skipping lines until
     * the pattern is found.
     */
    private String getNumberOfFrames(BufferedReader reader) throws IOException {
        Matcher m;
        do {
            String line = reader.readLine();
            if (line == null) {
                return null;
            }
            m = NO_OF_FRAMES_PATTERN.matcher(line);
        } while (!m.matches());
        return m.group(1);
    }

    private void writeAverageFrameRate() {
        FileWriter writer = null;
        try {
            Log.w(TAG, "file: " + LOG_FILE);
            writer = new FileWriter(LOG_FILE, true);
            writer.write(String.format("%s:%.2f\n", sTestCaseName,
                    getAverageFrameRate(sEndFrameNo - sStartFrameNo, sDuration)));
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
        String cmd = String.format(GET_APP_FRAMERATE_TMPL, sActivityName);
        try {
            process = Runtime.getRuntime().exec(cmd);
            int status = process.waitFor();
            if (status != 0) {
                Logger.err.println(String.format("// Shell command %s status was %s",
                        cmd, status));
            }
            reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String numFrames = getNumberOfFrames(reader);
            if (numFrames != null) {
                if ("start".equals(mStatus)) {
                    sStartFrameNo = Integer.parseInt(numFrames);
                    sStartTime = System.currentTimeMillis();
                } else if ("end".equals(mStatus)) {
                    sEndFrameNo = Integer.parseInt(numFrames);
                    sEndTime = System.currentTimeMillis();
                    sDuration = (float) ((sEndTime - sStartTime) / 1000.0d);
                    writeAverageFrameRate();
                }
            }
        } catch (Exception e) {
            Logger.err.println("// Exception from " + cmd + ":");
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
