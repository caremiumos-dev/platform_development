/*
 * Copyright (C) 2013 The Android Open Source Project
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

import android.util.Log;

public abstract class Logger {
    private static final String TAG = "Monkey";

    public static Logger out = new Logger() {
        @Override
        public void println(String str) {
            if (stdout) {
                System.out.println(str);
            }
            if (logcat) {
                Log.i(TAG, str);
            }
        }
    };

    public static Logger err = new Logger() {
        @Override
        public void println(String str) {
            if (stdout) {
                System.err.println(str);
            }
            if (logcat) {
                Log.w(TAG, str);
            }
        }
    };

    public static Logger xtcDebug = new Logger() {
        @Override
        public void println(String str) {
            System.err.println(str);
            Log.w(TAG, str);
        }
    };

    public static boolean stdout = true;
    public static boolean logcat = false;

    public static void error(String str, Throwable th) {
        err.println(str);
        err.println(Log.getStackTraceString(th));
    }

    public static void xtcError(String str, Throwable th) {
        xtcDebug.println(str);
        xtcDebug.println(Log.getStackTraceString(th));
    }

    public abstract void println(String str);
}
