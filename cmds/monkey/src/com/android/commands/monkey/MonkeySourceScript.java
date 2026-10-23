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

import android.content.ComponentName;
import android.os.SystemClock;

import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.NoSuchElementException;
import java.util.Random;

/**
 * monkey event queue. It takes a script to produce events
 *
 * sample script format:
 * <pre>
 *      type= raw events
 *      count= 10
 *      speed= 1.0
 *      start data >>
 *      DispatchPointer(5109520,5109520,0,230.75429,458.1814,0.20784314,0.06666667,0,0.0,0.0,0,0)
 *      ...
 * </pre>
 */
public class MonkeySourceScript implements MonkeyEventSource {
    private int mEventCountInScript = 0; // total number of events in the script
    private int mVerbose = 0;
    private double mSpeed = 1.0;
    private String mScriptFileName;
    private MonkeyEventQueue mQ;
    private long mLastRecordedDownTimeKey = 0;
    private long mLastRecordedDownTimeMotion = 0;
    private long mLastExportDownTimeKey = 0;
    private long mLastExportDownTimeMotion = 0;
    private long mLastExportEventTime = -1;
    private long mLastRecordedEventTime = -1;

    // adjust key down time
    private float[] mLastX = new float[2];
    private float[] mLastY = new float[2];
    private long mScriptStartTime = -1;
    private long mMonkeyStartTime = -1;

    private static final boolean THIS_DEBUG = false;
    // a line at the end of the header
    private static final String STARTING_DATA_LINE = "start data >>";
    private boolean mFileOpened = false;

    private static int LONGPRESS_WAIT_TIME = 2000; // wait time for the long press

    private long mProfileWaitTime = 5000; // Wait time for each user profile

    private long mDeviceSleepTime = 30000; // Device sleep time

    FileInputStream mFStream;
    DataInputStream mInputStream;
    BufferedReader mBufferedReader;

    // event count for the script
    private static final int MAX_ONE_TIME_READS = 100;

    // Script file header macros
    private static final String HEADER_COUNT = "count=";
    private static final String HEADER_SPEED = "speed=";
    private static final String HEADER_LINE_BY_LINE = "linebyline";

    // Event types
    private static final String EVENT_KEYWORD_ACTIVITY = "LaunchActivity";
    private static final String EVENT_KEYWORD_INSTRUMENTATION = "LaunchInstrumentation";
    private static final String EVENT_KEYWORD_WAIT = "UserWait";
    private static final String EVENT_KEYWORD_LONGPRESS = "LongPress";
    private static final String EVENT_KEYWORD_POINTER = "DispatchPointer";
    private static final String EVENT_KEYWORD_TRACKBALL = "DispatchTrackball";
    private static final String EVENT_KEYWORD_ROTATION = "RotateScreen";
    private static final String EVENT_KEYWORD_FLIP = "DispatchFlip";
    private static final String EVENT_KEYWORD_KEY = "DispatchKey";
    private static final String EVENT_KEYWORD_INPUT_STRING = "DispatchString";
    private static final String EVENT_KEYWORD_PRESSANDHOLD = "PressAndHold";
    private static final String EVENT_KEYWORD_DRAG = "Drag";
    private static final String EVENT_KEYWORD_PINCH_ZOOM = "PinchZoom";
    private static final String EVENT_KEYWORD_START_FRAMERATE_CAPTURE = "StartCaptureFramerate";
    private static final String EVENT_KEYWORD_END_FRAMERATE_CAPTURE = "EndCaptureFramerate";
    private static final String EVENT_KEYWORD_START_APP_FRAMERATE_CAPTURE =
            "StartCaptureAppFramerate";
    private static final String EVENT_KEYWORD_END_APP_FRAMERATE_CAPTURE = "EndCaptureAppFramerate";
    private static final String EVENT_KEYWORD_POWERLOG = "PowerLog";
    private static final String EVENT_KEYWORD_WRITEPOWERLOG = "WriteLog";
    private static final String EVENT_KEYWORD_RUNCMD = "RunCmd";
    private static final String EVENT_KEYWORD_TAP = "Tap";
    private static final String EVENT_KEYWORD_PROFILE_WAIT = "ProfileWait";
    private static final String EVENT_KEYWORD_DEVICE_WAKEUP = "DeviceWakeUp";
    private static final String EVENT_KEYWORD_KEYPRESS = "DispatchPress";

    // a parameter for the speed of the script
    private boolean mReadScriptLineByLine = false;
    private static final long SLEEP_COMPENSATE_DIFF = 16;

    public MonkeySourceScript(Random random, String filename, long throttle,
            boolean randomizeThrottle, long profileWaitTime, long deviceSleepTime) {
        mScriptFileName = filename;
        mQ = new MonkeyEventQueue(random, throttle, randomizeThrottle);
        mProfileWaitTime = profileWaitTime;
        mDeviceSleepTime = deviceSleepTime;
    }

    private void resetValue() {
        mLastRecordedDownTimeKey = 0;
        mLastRecordedDownTimeMotion = 0;
        mLastRecordedEventTime = -1;
        mLastExportDownTimeKey = 0;
        mLastExportDownTimeMotion = 0;
        mLastExportEventTime = -1;
    }

    private boolean readHeader() throws IOException {
        mFileOpened = true;

        mFStream = new FileInputStream(mScriptFileName);
        mInputStream = new DataInputStream(mFStream);
        mBufferedReader = new BufferedReader(new InputStreamReader(mInputStream));

        String line;
        while ((line = mBufferedReader.readLine()) != null) {
            line = line.trim();

            if (line.indexOf(HEADER_COUNT) >= 0) {
                try {
                    String value = line.substring(HEADER_COUNT.length() + 1).trim();
                    mEventCountInScript = Integer.parseInt(value);
                } catch (NumberFormatException e) {
                    Logger.err.println("" + e);
                    return false;
                }
            } else if (line.indexOf(HEADER_SPEED) >= 0) {
                try {
                    String value = line.substring(HEADER_COUNT.length() + 1).trim();
                    mSpeed = Double.parseDouble(value);
                } catch (NumberFormatException e) {
                    Logger.err.println("" + e);
                    return false;
                }
            } else if (line.indexOf(HEADER_LINE_BY_LINE) >= 0) {
                mReadScriptLineByLine = true;
            } else if (line.indexOf(STARTING_DATA_LINE) >= 0) {
                return true;
            }
        }
        return false;
    }

    private int readLines() throws IOException {
        String line;
        for (int i = 0; i < MAX_ONE_TIME_READS; i++) {
            line = mBufferedReader.readLine();
            if (line == null) {
                return i;
            }
            line.trim();
            processLine(line);
        }
        return MAX_ONE_TIME_READS;
    }

    private int readOneLine() throws IOException {
        String line = mBufferedReader.readLine();
        if (line == null) {
            return 0;
        }
        line.trim();
        processLine(line);
        return 1;
    }

    private void closeFile() throws IOException {
        mFileOpened = false;
        try {
            mFStream.close();
            mInputStream.close();
        } catch (NullPointerException e) {
            // File was never opened so it's ok to get a NullPointerException
        }
    }

    private void readNextBatch() throws IOException {
        if (!mFileOpened) {
            resetValue();
            readHeader();
        }

        int linesRead = 0;
        if (mReadScriptLineByLine) {
            linesRead = readOneLine();
        } else {
            linesRead = readLines();
        }

        if (linesRead == 0) {
            closeFile();
        }
    }

    private void needSleep(long time) {
        if (time < 1) {
            return;
        }
        try {
            Thread.sleep(time);
        } catch (InterruptedException e) {
            // ignore
        }
    }

    private void adjustKeyEventTime(MonkeyKeyEvent e) {
        if (e.getEventTime() < 0) {
            return;
        }
        long thisDownTime = 0;
        long thisEventTime = 0;
        long expectedDelay = 0;

        if (mLastRecordedEventTime <= 0) {
            thisEventTime = SystemClock.uptimeMillis();
            thisDownTime = thisEventTime;
        } else {
            if (e.getDownTime() != mLastRecordedDownTimeKey) {
                thisDownTime = e.getDownTime();
            } else {
                thisDownTime = mLastExportDownTimeKey;
            }
            expectedDelay = (long) ((e.getEventTime() - mLastRecordedEventTime) * mSpeed);
            thisEventTime = mLastExportEventTime + expectedDelay;
            // add sleep to simulate the delay
            needSleep(expectedDelay - SLEEP_COMPENSATE_DIFF);
        }
        mLastRecordedDownTimeKey = e.getDownTime();
        mLastRecordedEventTime = e.getEventTime();
        e.setDownTime(thisDownTime);
        e.setEventTime(thisEventTime);
        mLastExportDownTimeKey = thisDownTime;
        mLastExportEventTime = thisEventTime;
    }

    private void adjustMotionEventTime(MonkeyMotionEvent e) {
        long thisEventTime = SystemClock.uptimeMillis();
        long thisDownTime = e.getDownTime();

        if (thisDownTime == mLastRecordedDownTimeMotion) {
            e.setDownTime(mLastExportDownTimeMotion);
        } else {
            mLastRecordedDownTimeMotion = thisDownTime;
            e.setDownTime(thisEventTime);
            mLastExportDownTimeMotion = thisEventTime;
        }
        e.setEventTime(thisEventTime);
    }

    /**
     * Process an event string and add events to the queue.
     */
    private void handleEvent(String event, String[] args) {
        // Handle old-style key events.
        if (event.indexOf(EVENT_KEYWORD_KEY) >= 0 && args.length == 8) {
            try {
                Logger.out.println(" old key\n");
                long downTime = Long.parseLong(args[0]);
                long upTime = Long.parseLong(args[1]);
                int action = Integer.parseInt(args[2]);
                int keyCode = Integer.parseInt(args[3]);
                int repeatCount = Integer.parseInt(args[4]);
                int metaState = Integer.parseInt(args[5]);
                int deviceId = Integer.parseInt(args[6]);
                int scancode = Integer.parseInt(args[7]);

                MonkeyKeyEvent e = new MonkeyKeyEvent(downTime, upTime, action, keyCode,
                        repeatCount, metaState, deviceId, scancode);

                Logger.out.println(" Key code " + keyCode + "\n");
                mQ.addLast(e);
                Logger.out.println("Added key up \n");
                return;
            } catch (NumberFormatException e) {
                // ignore
            }
        }

        // Handle old-style pointer events.
        if ((event.indexOf(EVENT_KEYWORD_POINTER) >= 0
                || event.indexOf(EVENT_KEYWORD_TRACKBALL) >= 0) && args.length == 12) {
            try {
                long downTime = Long.parseLong(args[0]);
                long upTime = Long.parseLong(args[1]);
                int action = Integer.parseInt(args[2]);
                float x = Float.parseFloat(args[3]);
                float y = Float.parseFloat(args[4]);
                float pressure = Float.parseFloat(args[5]);
                float size = Float.parseFloat(args[6]);
                int metaState = Integer.parseInt(args[7]);
                float xPrecision = Float.parseFloat(args[8]);
                float yPrecision = Float.parseFloat(args[9]);
                int deviceId = Integer.parseInt(args[10]);
                int edgeFlags = Integer.parseInt(args[11]);

                MonkeyMotionEvent e;
                if (event.indexOf("Pointer") > 0) {
                    e = new MonkeyTouchEvent(action);
                } else {
                    e = new MonkeyTrackballEvent(action);
                }

                e.setDownTime(downTime)
                        .setEventTime(upTime)
                        .setMetaState(metaState)
                        .setPrecision(xPrecision, yPrecision)
                        .setDeviceId(deviceId)
                        .setEdgeFlags(edgeFlags)
                        .addPointer(0, x, y, pressure, size);
                mQ.addLast(e);
                return;
            } catch (NumberFormatException e) {
                // ignore
            }
        }

        // Handle new-style pointer events.
        if ((event.indexOf(EVENT_KEYWORD_POINTER) >= 0
                || event.indexOf(EVENT_KEYWORD_TRACKBALL) >= 0) && args.length == 13) {
            try {
                long downTime = Long.parseLong(args[0]);
                long upTime = Long.parseLong(args[1]);
                int action = Integer.parseInt(args[2]);
                float x = Float.parseFloat(args[3]);
                float y = Float.parseFloat(args[4]);
                float pressure = Float.parseFloat(args[5]);
                float size = Float.parseFloat(args[6]);
                int metaState = Integer.parseInt(args[7]);
                float xPrecision = Float.parseFloat(args[8]);
                float yPrecision = Float.parseFloat(args[9]);
                int deviceId = Integer.parseInt(args[10]);
                int edgeFlags = Integer.parseInt(args[11]);
                int pointerId = Integer.parseInt(args[12]);

                MonkeyMotionEvent e;
                if (event.indexOf("Pointer") > 0) {
                    if (action == 5) {
                        e = new MonkeyTouchEvent((pointerId << 8) | 5).setIntermediateNote(true);
                    } else {
                        e = new MonkeyTouchEvent(action);
                    }
                    if (mScriptStartTime < 0) {
                        mMonkeyStartTime = SystemClock.uptimeMillis();
                        mScriptStartTime = upTime;
                    }
                } else {
                    e = new MonkeyTrackballEvent(action);
                }

                if (pointerId == 1) {
                    e.setDownTime(downTime)
                            .setEventTime(upTime)
                            .setMetaState(metaState)
                            .setPrecision(xPrecision, yPrecision)
                            .setDeviceId(deviceId)
                            .setEdgeFlags(edgeFlags)
                            .addPointer(0, mLastX[0], mLastY[0], pressure, size)
                            .addPointer(1, x, y, pressure, size);
                    mLastX[1] = x;
                    mLastY[1] = y;
                } else if (pointerId == 0) {
                    e.setDownTime(downTime)
                            .setEventTime(upTime)
                            .setMetaState(metaState)
                            .setPrecision(xPrecision, yPrecision)
                            .setDeviceId(deviceId)
                            .setEdgeFlags(edgeFlags)
                            .addPointer(0, x, y, pressure, size);
                    if (action == 6) { // ACTION_POINTER_UP
                        e.addPointer(1, mLastX[1], mLastY[1]);
                    }
                    mLastX[0] = x;
                    mLastY[0] = y;
                }

                if (mReadScriptLineByLine) {
                    long timeDiff = SystemClock.uptimeMillis() - mMonkeyStartTime;
                    long scriptTimeDiff = upTime - mScriptStartTime;
                    if (timeDiff < scriptTimeDiff) {
                        mQ.addLast(new MonkeyWaitEvent(scriptTimeDiff - timeDiff));
                    }
                }

                mQ.addLast(e);
                return;
            } catch (NumberFormatException e) {
                // ignore
            }
        }

        // Handle rotation event.
        if (event.indexOf(EVENT_KEYWORD_ROTATION) >= 0 && args.length == 2) {
            try {
                int rotationDegree = Integer.parseInt(args[0]);
                int persist = Integer.parseInt(args[1]);
                if (rotationDegree == 0 || rotationDegree == 1
                        || rotationDegree == 2 || rotationDegree == 3) {
                    mQ.addLast(new MonkeyRotationEvent(rotationDegree, persist != 0));
                    return;
                }
                return;
            } catch (NumberFormatException e) {
                // ignore
            }
        }

        // Handle tap event.
        if (event.indexOf(EVENT_KEYWORD_TAP) >= 0 && args.length >= 2) {
            try {
                float x = Float.parseFloat(args[0]);
                float y = Float.parseFloat(args[1]);
                long tapDuration = 0;
                if (args.length == 3) {
                    tapDuration = Long.parseLong(args[2]);
                }

                long downTime = SystemClock.uptimeMillis();
                mQ.addLast(new MonkeyTouchEvent(0)
                        .setDownTime(downTime)
                        .setEventTime(downTime)
                        .addPointer(0, x, y, 1.0f, 5.0f));
                if (tapDuration > 0) {
                    mQ.addLast(new MonkeyWaitEvent(tapDuration));
                }
                mQ.addLast(new MonkeyTouchEvent(1)
                        .setDownTime(downTime)
                        .setEventTime(downTime)
                        .addPointer(0, x, y, 1.0f, 5.0f));
                return;
            } catch (NumberFormatException e) {
                Logger.err.println("// " + e.toString());
                return;
            }
        }

        // Handle press-and-hold event.
        if (event.indexOf(EVENT_KEYWORD_PRESSANDHOLD) >= 0 && args.length == 3) {
            try {
                float x = Float.parseFloat(args[0]);
                float y = Float.parseFloat(args[1]);
                long pressDuration = Long.parseLong(args[2]);
                long downTime = SystemClock.uptimeMillis();

                MonkeyMotionEvent downEvent = new MonkeyTouchEvent(0)
                        .setDownTime(downTime)
                        .setEventTime(downTime)
                        .addPointer(0, x, y, 1.0f, 5.0f);
                MonkeyWaitEvent waitEvent = new MonkeyWaitEvent(pressDuration);
                new MonkeyTouchEvent(1)
                        .setDownTime(downTime + pressDuration)
                        .setEventTime(downTime + pressDuration)
                        .addPointer(0, x, y, 1.0f, 5.0f);

                mQ.addLast(downEvent);
                mQ.addLast(waitEvent);
                mQ.addLast(waitEvent);
                return;
            } catch (NumberFormatException e) {
                Logger.err.println("// " + e.toString());
                return;
            }
        }

        // Handle drag event.
        if (event.indexOf(EVENT_KEYWORD_DRAG) >= 0 && args.length == 5) {
            float xStart = Float.parseFloat(args[0]);
            float yStart = Float.parseFloat(args[1]);
            float xEnd = Float.parseFloat(args[2]);
            float yEnd = Float.parseFloat(args[3]);
            int stepCount = Integer.parseInt(args[4]);

            long downTime = SystemClock.uptimeMillis();
            long moveTime = SystemClock.uptimeMillis();
            if (stepCount > 0) {
                float xStep = (xEnd - xStart) / stepCount;
                float yStep = (yEnd - yStart) / stepCount;
                mQ.addLast(new MonkeyTouchEvent(0)
                        .setDownTime(downTime)
                        .setEventTime(moveTime)
                        .addPointer(0, xStart, yStart, 1.0f, 5.0f));
                for (int i = 0; i < stepCount; i++) {
                    xStart += xStep;
                    yStart += yStep;
                    mQ.addLast(new MonkeyTouchEvent(2)
                            .setDownTime(downTime)
                            .setEventTime(SystemClock.uptimeMillis())
                            .addPointer(0, xStart, yStart, 1.0f, 5.0f));
                }
                mQ.addLast(new MonkeyTouchEvent(1)
                        .setDownTime(downTime)
                        .setEventTime(SystemClock.uptimeMillis())
                        .addPointer(0, xStart, yStart, 1.0f, 5.0f));
            }
        }

        // Handle pinch/zoom event.
        if (event.indexOf(EVENT_KEYWORD_PINCH_ZOOM) >= 0 && args.length == 9) {
            float x1Start = Float.parseFloat(args[0]);
            float y1Start = Float.parseFloat(args[1]);
            float x2Start = Float.parseFloat(args[2]);
            float y2Start = Float.parseFloat(args[3]);
            float x1End = Float.parseFloat(args[4]);
            float y1End = Float.parseFloat(args[5]);
            float x2End = Float.parseFloat(args[6]);
            float y2End = Float.parseFloat(args[7]);
            int stepCount = Integer.parseInt(args[8]);

            long downTime = SystemClock.uptimeMillis();
            long moveTime = SystemClock.uptimeMillis();
            if (stepCount > 0) {
                float x1Step = (x2Start - x1Start) / stepCount;
                float y1Step = (y2Start - y1Start) / stepCount;
                float x2Step = (x2End - x1End) / stepCount;
                float y2Step = (y2End - y1End) / stepCount;

                mQ.addLast(new MonkeyTouchEvent(0)
                        .setDownTime(downTime)
                        .setEventTime(moveTime)
                        .addPointer(0, x1Start, y1Start, 1.0f, 5.0f));
                mQ.addLast(new MonkeyTouchEvent(261)
                        .setDownTime(downTime)
                        .addPointer(0, x1Start, y1Start)
                        .addPointer(1, x1End, y1End)
                        .setIntermediateNote(true));
                for (int i = 0; i < stepCount; i++) {
                    x1Start += x1Step;
                    y1Start += y1Step;
                    x1End += x2Step;
                    y1End += y2Step;
                    mQ.addLast(new MonkeyTouchEvent(2)
                            .setDownTime(downTime)
                            .setEventTime(SystemClock.uptimeMillis())
                            .addPointer(0, x1Start, y1Start, 1.0f, 5.0f)
                            .addPointer(1, x1End, y1End, 1.0f, 5.0f));
                }
                mQ.addLast(new MonkeyTouchEvent(6)
                        .setDownTime(downTime)
                        .setEventTime(SystemClock.uptimeMillis())
                        .addPointer(0, x1Start, y1Start)
                        .addPointer(1, x1End, y1End));
            }
        }

        // Handle flip event.
        if (event.indexOf(EVENT_KEYWORD_FLIP) >= 0 && args.length == 1) {
            mQ.addLast(new MonkeyFlipEvent(Boolean.parseBoolean(args[0])));
        }

        // Handle activity event.
        if (event.indexOf(EVENT_KEYWORD_ACTIVITY) >= 0 && args.length >= 2) {
            long alarmTime = 0;
            ComponentName app = new ComponentName(args[0], args[1]);
            if (args.length > 2) {
                try {
                    alarmTime = Long.parseLong(args[2]);
                } catch (NumberFormatException e) {
                    Logger.err.println("// " + e.toString());
                    return;
                }
            }
            if (args.length == 2) {
                mQ.addLast(new MonkeyActivityEvent(app));
            } else {
                mQ.addLast(new MonkeyActivityEvent(app, alarmTime));
            }
            return;
        }

        // Handle device wakeup event.
        if (event.indexOf(EVENT_KEYWORD_DEVICE_WAKEUP) >= 0) {
            long sleepTime = mDeviceSleepTime;
            mQ.addLast(new MonkeyActivityEvent(new ComponentName(
                    "com.google.android.powerutil",
                    "com.google.android.powerutil.WakeUpScreen"), sleepTime));
            mQ.addLast(new MonkeyKeyEvent(0, 7));
            mQ.addLast(new MonkeyKeyEvent(1, 7));
            mQ.addLast(new MonkeyWaitEvent(sleepTime + 3000));
            mQ.addLast(new MonkeyKeyEvent(0, 82));
            mQ.addLast(new MonkeyKeyEvent(1, 82));
            mQ.addLast(new MonkeyKeyEvent(0, 4));
            mQ.addLast(new MonkeyKeyEvent(1, 4));
            return;
        }

        // Handle instrumentation event.
        if (event.indexOf(EVENT_KEYWORD_INSTRUMENTATION) >= 0 && args.length == 2) {
            mQ.addLast(new MonkeyInstrumentationEvent(args[0], args[1]));
            return;
        }

        // Handle wait event.
        if (event.indexOf(EVENT_KEYWORD_WAIT) >= 0 && args.length == 1) {
            try {
                mQ.addLast(new MonkeyWaitEvent(Integer.parseInt(args[0])));
                return;
            } catch (NumberFormatException e) {
                // ignore
            }
        }

        // Handle profile wait event.
        if (event.indexOf(EVENT_KEYWORD_PROFILE_WAIT) >= 0) {
            mQ.addLast(new MonkeyWaitEvent(mProfileWaitTime));
            return;
        }

        // Handle key press event.
        if (event.indexOf(EVENT_KEYWORD_KEYPRESS) >= 0 && args.length == 1) {
            int keyCode = MonkeySourceRandom.getKeyCode(args[0]);
            if (keyCode == 0) {
                return;
            }
            mQ.addLast(new MonkeyKeyEvent(0, keyCode));
            mQ.addLast(new MonkeyKeyEvent(1, keyCode));
            return;
        }

        // Handle long press event.
        if (event.indexOf(EVENT_KEYWORD_LONGPRESS) >= 0) {
            mQ.addLast(new MonkeyKeyEvent(0, 23));
            mQ.addLast(new MonkeyWaitEvent(LONGPRESS_WAIT_TIME));
            mQ.addLast(new MonkeyKeyEvent(1, 23));
        }

        // Handle power log event.
        if (event.indexOf(EVENT_KEYWORD_POWERLOG) >= 0 && args.length > 0) {
            String powerLog = args[0];
            if (args.length == 1) {
                mQ.addLast(new MonkeyPowerEvent(powerLog));
            } else if (args.length == 2) {
                mQ.addLast(new MonkeyPowerEvent(powerLog, args[1]));
            }
        }

        // Handle write power log event.
        if (event.indexOf(EVENT_KEYWORD_WRITEPOWERLOG) >= 0) {
            mQ.addLast(new MonkeyPowerEvent());
        }

        // Handle run command event.
        if (event.indexOf(EVENT_KEYWORD_RUNCMD) >= 0 && args.length == 1) {
            mQ.addLast(new MonkeyCommandEvent(args[0]));
        }

        // Handle input string event.
        if (event.indexOf(EVENT_KEYWORD_INPUT_STRING) >= 0 && args.length == 1) {
            mQ.addLast(new MonkeyCommandEvent("input text " + args[0]));
            return;
        }

        // Handle start frame rate capture event.
        if (event.indexOf(EVENT_KEYWORD_START_FRAMERATE_CAPTURE) >= 0) {
            mQ.addLast(new MonkeyGetFrameRateEvent("start"));
            return;
        }

        // Handle end frame rate capture event.
        if (event.indexOf(EVENT_KEYWORD_END_FRAMERATE_CAPTURE) >= 0 && args.length == 1) {
            mQ.addLast(new MonkeyGetFrameRateEvent("end", args[0]));
            return;
        }

        // Handle start app frame rate capture event.
        if (event.indexOf(EVENT_KEYWORD_START_APP_FRAMERATE_CAPTURE) >= 0 && args.length == 1) {
            mQ.addLast(new MonkeyGetAppFrameRateEvent("start", args[0]));
        } else if (event.indexOf(EVENT_KEYWORD_END_APP_FRAMERATE_CAPTURE) >= 0
                && args.length == 2) {
            mQ.addLast(new MonkeyGetAppFrameRateEvent("end", args[0], args[1]));
        }
    }

    private void processLine(String line) {
        int index1 = line.indexOf('(');
        int index2 = line.indexOf(')');
        if (index1 < 0 || index2 < 0) {
            return;
        }
        String[] args = line.substring(index1 + 1, index2).split(",");
        for (int i = 0; i < args.length; i++) {
            args[i] = args[i].trim();
        }
        handleEvent(line, args);
    }

    @Override
    public MonkeyEvent getNextEvent() {
        if (mQ.isEmpty()) {
            try {
                readNextBatch();
            } catch (IOException e) {
                return null;
            }
        }

        try {
            MonkeyEvent e = mQ.getFirst();
            mQ.removeFirst();
            if (e.getEventType() == MonkeyEvent.EVENT_TYPE_KEY) {
                adjustKeyEventTime((MonkeyKeyEvent) e);
            } else if (e.getEventType() == MonkeyEvent.EVENT_TYPE_TOUCH
                    || e.getEventType() == MonkeyEvent.EVENT_TYPE_TRACKBALL) {
                adjustMotionEventTime((MonkeyMotionEvent) e);
            }
            return e;
        } catch (NoSuchElementException e) {
            return null;
        }
    }

    @Override
    public void setVerbose(int verbose) {
        mVerbose = verbose;
    }

    @Override
    public boolean validate() {
        try {
            boolean header = readHeader();
            closeFile();
            if (mVerbose > 0) {
                Logger.out.println("Replaying " + mEventCountInScript + " events with speed "
                        + mSpeed);
            }
            return header;
        } catch (IOException e) {
            return false;
        }
    }
}
