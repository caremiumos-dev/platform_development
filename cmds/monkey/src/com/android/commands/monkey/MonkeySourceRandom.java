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
import android.graphics.PointF;
import android.hardware.display.DisplayManagerGlobal;
import android.os.SystemClock;
import android.view.Display;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;

import java.util.List;
import java.util.Random;

/**
 * monkey event generator
 */
public class MonkeySourceRandom implements MonkeyEventSource {
    /** Key events that move around the UI. */
    private static final int[] NAV_KEYS = {
        KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
        KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
    };
    /**
     * Key events that perform major navigation options (so should be used
     * sparingly).
     */
    private static final int[] MAJOR_NAV_KEYS = {
        KeyEvent.KEYCODE_MENU, /* KeyEvent.KEYCODE_SOFT_RIGHT */KeyEvent.KEYCODE_DPAD_CENTER,
    };
    /** Key events that perform system operations. */
    private static final int[] SYS_KEYS = {
        3, 4, 5, 6, 24, 25, 164, 91,
    };
    /** If a physical key exists? */
    private static final boolean[] PHYSICAL_KEY_EXISTS = new boolean[KeyEvent.getMaxKeyCode() + 1];

    static {
        for (int i = 0; i < PHYSICAL_KEY_EXISTS.length; ++i) {
            PHYSICAL_KEY_EXISTS[i] = true;
        }
        // Only examine SYS_KEYS
        for (int i = 0; i < SYS_KEYS.length; ++i) {
            PHYSICAL_KEY_EXISTS[SYS_KEYS[i]] = KeyCharacterMap.deviceHasKey(SYS_KEYS[i]);
        }
    }

    public static final int FACTOR_TOUCH = 0;
    public static final int FACTOR_MOTION = 1;
    public static final int FACTOR_PINCHZOOM = 2;
    public static final int FACTOR_TRACKBALL = 3;
    public static final int FACTOR_ROTATION = 4;
    public static final int FACTOR_PERMISSION = 5;
    public static final int FACTOR_NAV = 6;
    public static final int FACTOR_MAJORNAV = 7;
    public static final int FACTOR_SYSOPS = 8;
    public static final int FACTOR_APPSWITCH = 9;
    public static final int FACTOR_FLIP = 10;
    public static final int FACTOR_ANYTHING = 11;
    public static final int FACTORZ_COUNT = 12; // should be last

    private static final int GESTURE_TAP = 0;
    private static final int GESTURE_DRAG = 1;
    private static final int GESTURE_PINCH_OR_ZOOM = 2;

    private static final int[] SCREEN_ROTATION_DEGREES = {0, 1, 2, 3};

    private Random mRandom;

    private float[] mFactors = new float[FACTORZ_COUNT];
    private List<ComponentName> mMainApps;
    private int mEventCount = 0; // total number of events generated so far
    private MonkeyEventQueue mQ;
    private int mVerbose = 0;
    private long mThrottle = 0;
    private boolean mKeyboardOpen = false;

    private MonkeyPermissionUtil mPermissionUtil;

    public static String getKeyName(int keyCode) {
        return KeyEvent.keyCodeToString(keyCode);
    }

    public static int getKeyCode(String keyName) {
        return KeyEvent.keyCodeFromString(keyName);
    }

    public MonkeySourceRandom(Random random, List<ComponentName> MainApps,
            long throttle, boolean randomizeThrottle, boolean permissionTargetSystem) {
        // default values for random distributions
        // note, these are straight percentages, to match user input (cmd line
        // args)
        // but they will be converted to 0..1 values before the main loop runs.
        mFactors[FACTOR_TOUCH] = 15.0f;
        mFactors[FACTOR_MOTION] = 10.0f;
        mFactors[FACTOR_TRACKBALL] = 15.0f;
        mFactors[FACTOR_ROTATION] = 0.0f;
        mFactors[FACTOR_NAV] = 25.0f;
        mFactors[FACTOR_MAJORNAV] = 15.0f;
        mFactors[FACTOR_SYSOPS] = 2.0f;
        mFactors[FACTOR_APPSWITCH] = 2.0f;
        mFactors[FACTOR_FLIP] = 1.0f;
        mFactors[FACTOR_PERMISSION] = 0.0f;
        mFactors[FACTOR_ANYTHING] = 13.0f;
        mFactors[FACTOR_PINCHZOOM] = 2.0f;

        mRandom = random;
        mMainApps = MainApps;
        mQ = new MonkeyEventQueue(random, throttle, randomizeThrottle);
        mPermissionUtil = new MonkeyPermissionUtil();
        mPermissionUtil.setTargetSystemPackages(permissionTargetSystem);
    }

    /**
     * Adjust the percentages (after applying user values) and then normalize to
     * 0..1
     */
    private boolean adjustEventFactors() {
        // go through all values and compute totals for user supplied and
        // default values
        float userSum = 0.0f;
        float defaultSum = 0.0f;
        int defaultCount = 0;
        for (int i = 0; i < FACTORZ_COUNT; ++i) {
            if (mFactors[i] <= 0.0f) { // user supplied and we're always negative
                userSum -= mFactors[i];
            } else { // default value
                defaultSum += mFactors[i];
                ++defaultCount;
            }
        }

        // if the user completely apportioned the values, just take them as is
        if (userSum > 100.0f) {
            Logger.err.println("** Event weights > 100%");
            return false;
        }

        // if we don't have enough default values, and the user values don't add
        // up to 100, complain
        if (defaultCount == 0 && (userSum < 99.9f || userSum > 100.1f)) {
            Logger.err.println("** Event weights != 100%");
            return false;
        }

        // compute the adjustment necessary for the default values
        float defaultsTarget = (100.0f - userSum);
        float defaultsAdjustment = defaultsTarget / defaultSum;

        // correct all values
        for (int i = 0; i < FACTORZ_COUNT; ++i) {
            if (mFactors[i] <= 0.0f) { // user supplied value: remove offset
                mFactors[i] = -mFactors[i];
            } else { // default value: apply adjustment
                mFactors[i] *= defaultsAdjustment;
            }
        }

        if (mVerbose > 0) {
            Logger.out.println("// Event percentages:");
            for (int i = 0; i < FACTORZ_COUNT; ++i) {
                Logger.out.println("//   " + i + ": " + mFactors[i] + "%");
            }
        }

        if (!validateKeys()) {
            return false;
        }

        // calculate cumulative distribution of each factor
        float sum = 0.0f;
        for (int i = 0; i < FACTORZ_COUNT; ++i) {
            sum += mFactors[i] / 100.0f;
            mFactors[i] = sum;
        }

        return true;
    }

    /** Validate that the selected key factors are allowed on this device. */
    private static boolean validateKeyCategory(String catName, int[] keys, float factor) {
        if (factor < 0.1f) {
            return true;
        }
        for (int i = 0; i < keys.length; ++i) {
            if (PHYSICAL_KEY_EXISTS[keys[i]]) {
                return true;
            }
        }
        Logger.err.println("** " + catName + " has no physical keys but with factor " + factor
                + "%.");
        return false;
    }

    private boolean validateKeys() {
        return validateKeyCategory("NAV_KEYS", NAV_KEYS, mFactors[FACTOR_NAV])
                && validateKeyCategory("MAJOR_NAV_KEYS", MAJOR_NAV_KEYS, mFactors[FACTOR_MAJORNAV])
                && validateKeyCategory("SYS_KEYS", SYS_KEYS, mFactors[FACTOR_SYSOPS]);
    }

    /**
     * @param random the random number generator to use
     * @param display the display to get the coordinates from
     * @return a random point on the display
     */
    private static PointF randomPoint(Random random, Display display) {
        return new PointF(random.nextInt(display.getWidth()),
                random.nextInt(display.getHeight()));
    }

    private static PointF randomVector(Random random) {
        return new PointF((random.nextFloat() - 0.5f) * 50, (random.nextFloat() - 0.5f) * 50);
    }

    private static void randomWalk(Random random, Display display, PointF point, PointF vector) {
        point.x = (float) Math.max(Math.min(point.x + random.nextFloat() * vector.x,
                display.getWidth()), 0);
        point.y = (float) Math.max(Math.min(point.y + random.nextFloat() * vector.y,
                display.getHeight()), 0);
    }

    private void generatePointerEvent(Random random, int gesture) {
        Display display = DisplayManagerGlobal.getInstance().getRealDisplay(Display.DEFAULT_DISPLAY);

        PointF p1 = randomPoint(random, display);
        PointF v1 = randomVector(random);

        long downTime = SystemClock.uptimeMillis();
        mQ.addLast(new MonkeyTouchEvent(KeyEvent.ACTION_DOWN)
                .setDownTime(downTime)
                .addPointer(0, p1.x, p1.y)
                .setIntermediateNote(false));

        if (gesture == GESTURE_DRAG) {
            int steps = random.nextInt(10);
            for (int i = 0; i < steps; i++) {
                randomWalk(random, display, p1, v1);

                mQ.addLast(new MonkeyTouchEvent(2 /* ACTION_MOVE */)
                        .setDownTime(downTime)
                        .addPointer(0, p1.x, p1.y)
                        .setIntermediateNote(true));
            }
        } else if (gesture == GESTURE_PINCH_OR_ZOOM) {
            PointF p2 = randomPoint(random, display);
            PointF v2 = randomVector(random);

            randomWalk(random, display, p1, v1);
            mQ.addLast(new MonkeyTouchEvent(261 /* ACTION_POINTER_DOWN | (1 << 8) */)
                    .setDownTime(downTime)
                    .addPointer(0, p1.x, p1.y)
                    .addPointer(1, p2.x, p2.y)
                    .setIntermediateNote(true));

            int steps = random.nextInt(10);
            for (int i = 0; i < steps; i++) {
                randomWalk(random, display, p1, v1);
                randomWalk(random, display, p2, v2);

                mQ.addLast(new MonkeyTouchEvent(2 /* ACTION_MOVE */)
                        .setDownTime(downTime)
                        .addPointer(0, p1.x, p1.y)
                        .addPointer(1, p2.x, p2.y)
                        .setIntermediateNote(true));
            }

            randomWalk(random, display, p1, v1);
            randomWalk(random, display, p2, v2);
            mQ.addLast(new MonkeyTouchEvent(262 /* ACTION_POINTER_UP | (1 << 8) */)
                    .setDownTime(downTime)
                    .addPointer(0, p1.x, p1.y)
                    .addPointer(1, p2.x, p2.y)
                    .setIntermediateNote(true));
        }

        randomWalk(random, display, p1, v1);
        mQ.addLast(new MonkeyTouchEvent(KeyEvent.ACTION_UP)
                .setDownTime(downTime)
                .addPointer(0, p1.x, p1.y)
                .setIntermediateNote(false));
    }

    private void generateTrackballEvent(Random random) {
        // generate a number of random trackball moves
        for (int i = 0; i < 10; i++) {
            mQ.addLast(new MonkeyTrackballEvent(2 /* ACTION_MOVE */)
                    .addPointer(0, random.nextInt(10) - 5, random.nextInt(10) - 5)
                    .setIntermediateNote(i > 0));
        }

        // if we have a 1 in 10 chance, add a press and release
        if (random.nextInt(10) == 0) {
            long downTime = SystemClock.uptimeMillis();

            mQ.addLast(new MonkeyTrackballEvent(KeyEvent.ACTION_DOWN)
                    .setDownTime(downTime)
                    .addPointer(0, 0, 0)
                    .setIntermediateNote(true));
            mQ.addLast(new MonkeyTrackballEvent(KeyEvent.ACTION_UP)
                    .setDownTime(downTime)
                    .addPointer(0, 0, 0)
                    .setIntermediateNote(false));
        }
    }

    private void generateRotationEvent(Random random) {
        mQ.addLast(new MonkeyRotationEvent(
                SCREEN_ROTATION_DEGREES[random.nextInt(SCREEN_ROTATION_DEGREES.length)],
                random.nextBoolean()));
    }

    private void generateEvents() {
        float cls = mRandom.nextFloat();
        int lastKey;

        if (cls < mFactors[FACTOR_TOUCH]) {
            generatePointerEvent(mRandom, GESTURE_TAP);
            return;
        } else if (cls < mFactors[FACTOR_MOTION]) {
            generatePointerEvent(mRandom, GESTURE_DRAG);
            return;
        } else if (cls < mFactors[FACTOR_PINCHZOOM]) {
            generatePointerEvent(mRandom, GESTURE_PINCH_OR_ZOOM);
            return;
        } else if (cls < mFactors[FACTOR_TRACKBALL]) {
            generateTrackballEvent(mRandom);
            return;
        } else if (cls < mFactors[FACTOR_ROTATION]) {
            generateRotationEvent(mRandom);
            return;
        } else if (cls < mFactors[FACTOR_PERMISSION]) {
            mQ.add(mPermissionUtil.generateRandomPermissionEvent(mRandom));
            return;
        }

        // The remaining event categories are injected as a simple key sequence.
        for (;;) {
            if (cls < mFactors[FACTOR_NAV]) {
                lastKey = NAV_KEYS[mRandom.nextInt(NAV_KEYS.length)];
            } else if (cls < mFactors[FACTOR_MAJORNAV]) {
                lastKey = MAJOR_NAV_KEYS[mRandom.nextInt(MAJOR_NAV_KEYS.length)];
            } else if (cls < mFactors[FACTOR_SYSOPS]) {
                lastKey = SYS_KEYS[mRandom.nextInt(SYS_KEYS.length)];
            } else if (cls < mFactors[FACTOR_APPSWITCH]) {
                mQ.addLast(new MonkeyActivityEvent(
                        mMainApps.get(mRandom.nextInt(mMainApps.size()))));
                return;
            } else if (cls < mFactors[FACTOR_FLIP]) {
                MonkeyFlipEvent e = new MonkeyFlipEvent(mKeyboardOpen);
                mKeyboardOpen = !mKeyboardOpen;
                mQ.addLast(e);
                return;
            } else {
                lastKey = mRandom.nextInt(KeyEvent.getMaxKeyCode() - 1) + 1;
            }
            // don't try to inject keys that will not be handled properly.
            if (lastKey != KeyEvent.KEYCODE_POWER
                    && lastKey != KeyEvent.KEYCODE_ENDCALL
                    && lastKey != 223 /* KEYCODE_SWITCH_CHARSET */
                    && lastKey != 276 /* KEYCODE_... */
                    && PHYSICAL_KEY_EXISTS[lastKey]) {
                mQ.addLast(new MonkeyKeyEvent(KeyEvent.ACTION_DOWN, lastKey));
                mQ.addLast(new MonkeyKeyEvent(KeyEvent.ACTION_UP, lastKey));
                return;
            }
        }
    }

    public void generateActivity() {
        mQ.addLast(new MonkeyActivityEvent(mMainApps.get(mRandom.nextInt(mMainApps.size()))));
    }

    @Override
    public MonkeyEvent getNextEvent() {
        if (mQ.isEmpty()) {
            generateEvents();
        }
        mEventCount++;
        MonkeyEvent e = mQ.getFirst();
        mQ.removeFirst();
        return e;
    }

    @Override
    public void setVerbose(int verbose) {
        mVerbose = verbose;
    }

    public void setFactors(int index, float factor) {
        mFactors[index] = factor;
    }

    public void setFactors(float[] factors) {
        int c = FACTORZ_COUNT;
        if (factors.length < c) {
            c = factors.length;
        }
        for (int i = 0; i < c; i++) {
            mFactors[i] = factors[i];
        }
    }

    @Override
    public boolean validate() {
        boolean ret = true;
        if (mFactors[FACTOR_PERMISSION] != 0.0f) {
            ret = mPermissionUtil.populatePermissionsMapping();
            if (ret && mVerbose >= 2) {
                mPermissionUtil.dump();
            }
        }
        return ret & adjustEventFactors();
    }
}
