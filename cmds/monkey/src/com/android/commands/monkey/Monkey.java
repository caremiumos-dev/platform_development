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

import android.app.ActivityManager;
import android.app.IActivityController;
import android.app.IActivityManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.IPackageManager;
import android.content.pm.ResolveInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.Debug;
import android.os.Environment;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.StrictMode;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.view.IWindowManager;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * Monkey is the application that runs the monkey.
 */
public class Monkey {
    private static final String TOMBSTONE_PREFIX = "tombstone_";

    // Path to the tombstone directory.
    private static final File TOMBSTONES_PATH = new File("/data/tombstones");

    // Debugging: Allow monkey to start any package.
    private static final int DEBUG_ALLOW_ANY_STARTS = 0;
    // Debugging: Allow monkey to restart any package.
    private static final int DEBUG_ALLOW_ANY_RESTARTS = 1;

    // The package to test. May be null.
    public static String currentPackage;
    // The intent to test. May be null.
    public static Intent currentIntent;

    private int mVerbose = 0;
    private String[] mArgs;
    private int mNextArg;
    private String mCurArgData;

    // Application we want to test.
    private ArrayList<ComponentName> mMainApps = new ArrayList<ComponentName>();
    // Categories we want to test.
    private ArrayList<String> mMainCategories = new ArrayList<String>();
    // Delay between events.
    long mThrottle = 0;
    boolean mRandomizeThrottle = false;
    // Number of events.
    int mCount = 1000;
    // Random seed.
    long mSeed = 0;
    // Runtime.
    long mRunTime = 0;
    long mStartTime = 0;

    boolean mCountEvents = true;
    boolean mRandomizeScript = false;
    boolean mScriptLog = false;

    private boolean mSendNoEvents;
    private boolean mGenerateHprof;
    private boolean mPermissionTargetSystem = false;

    private boolean mIgnoreCrashes;
    private boolean mIgnoreTimeouts;
    private boolean mIgnoreSecurityExceptions;
    private boolean mIgnoreNativeCrashes;
    private boolean mKillProcessAfterError;
    private boolean mMonitorNativeCrashes;

    private String mMatchDescription;
    private String mPkgBlacklistFile;
    private String mPkgWhitelistFile;

    private boolean mRequestAnrTraces = false;
    private boolean mRequestDumpsysMemInfo = false;
    private boolean mRequestAnrBugreport = false;
    private boolean mRequestWatchdogBugreport = false;
    private boolean mWatchdogWaiting = false;
    private boolean mRequestAppCrashBugreport = false;
    private boolean mGetPeriodicBugreport = false;
    private boolean mRequestPeriodicBugreport = false;
    private long mBugreportFrequency = 10;
    private boolean mRequestProcRank = false;
    private boolean mRequestBugreport = false;
    private String mSetupFileName = null;
    private ArrayList<String> mScriptFileNames = new ArrayList<String>();
    private int mServerPort = -1;

    private HashSet<Long> mTombstones = null;

    private IActivityManager mAm;
    private IPackageManager mPm;
    private IWindowManager mWm;

    private MonkeyNetworkMonitor mNetworkMonitor = new MonkeyNetworkMonitor();

    private String mReportProcessName;
    private String mMonkeyStatus;

    private boolean mAbort = false;

    float[] mFactors = new float[MonkeySourceRandom.FACTORZ_COUNT];

    Random mRandom = null;

    long mProfileWaitTime = 5000;
    long mDeviceSleepTime = 30000;

    // dropped event statistics
    long mDroppedKeyEvents = 0;
    long mDroppedPointerEvents = 0;
    long mDroppedTrackballEvents = 0;
    long mDroppedFlipEvents = 0;
    long mDroppedRotationEvents = 0;

    MonkeyEventSource mEventSource;

    /**
     * Command line parser for the monkey.
     */
    private String nextArg() {
        if (mNextArg >= mArgs.length) {
            return null;
        }
        String arg = mArgs[mNextArg];
        mNextArg++;
        return arg;
    }

    /**
     * Get the next option, if any. Returns null if there is no option.
     */
    private String nextOption() {
        if (mNextArg >= mArgs.length) {
            return null;
        }
        String arg = mArgs[mNextArg];
        if (!arg.startsWith("-")) {
            return null;
        }
        mNextArg++;
        if (arg.equals("--")) {
            return null;
        }
        if (arg.length() > 1 && arg.charAt(1) != '-') {
            if (arg.length() > 2) {
                mCurArgData = arg.substring(2);
                return arg.substring(0, 2);
            } else {
                mCurArgData = null;
                return arg;
            }
        }
        mCurArgData = null;
        Logger.err.println("arg=\"" + arg + "\" mCurArgData=\"" + mCurArgData
                + "\" mNextArg=" + mNextArg + " argwas=\"" + mArgs[mNextArg - 1]
                + "\" nextarg=\"" + mArgs[mNextArg] + "\"");
        return arg;
    }

    /**
     * Return the next data for the current option.
     */
    private String nextOptionData() {
        if (mCurArgData != null) {
            return mCurArgData;
        }
        if (mNextArg >= mArgs.length) {
            return null;
        }
        String data = mArgs[mNextArg];
        Logger.err.println("data=\"" + data + "\"");
        mNextArg++;
        return data;
    }

    /**
     * Return a long value for the current option.
     */
    private long nextOptionLong(String opt) {
        try {
            return Long.parseLong(nextOptionData());
        } catch (NumberFormatException e) {
            Logger.err.println("** Error: " + opt + " is not a number");
            throw e;
        }
    }

    /**
     * Parse the command line options.
     */
    private boolean processOptions() {
        if (mArgs.length < 1) {
            showUsage();
            return false;
        }

        try {
            HashSet<String> validPackages = new HashSet<String>();
            while (true) {
                String opt = nextOption();
                if (opt == null) {
                    MonkeyUtils.getPackageFilter().addValidPackages(validPackages);
                    if (mServerPort == -1) {
                        String countStr = nextArg();
                        if (countStr == null) {
                            Logger.err.println("** Error: Count not specified");
                            showUsage();
                            return false;
                        }
                        try {
                            mCount = Integer.parseInt(countStr);
                        } catch (NumberFormatException e) {
                            Logger.err.println("** Error: Count is not a number: \"" + countStr
                                    + "\"");
                            showUsage();
                            return false;
                        }
                    }
                    return true;
                }

                if (opt.equals("-s")) {
                    mSeed = nextOptionLong("Seed");
                } else if (opt.equals("-t")) {
                    mRunTime = nextOptionLong("Runtime");
                } else if (opt.equals("-p")) {
                    validPackages.add(nextOptionData());
                } else if (opt.equals("-c")) {
                    mMainCategories.add(nextOptionData());
                } else if (opt.equals("-v")) {
                    mVerbose++;
                } else if (opt.equals("--ignore-crashes")) {
                    mIgnoreCrashes = true;
                } else if (opt.equals("--ignore-timeouts")) {
                    mIgnoreTimeouts = true;
                } else if (opt.equals("--ignore-security-exceptions")) {
                    mIgnoreSecurityExceptions = true;
                } else if (opt.equals("--monitor-native-crashes")) {
                    mMonitorNativeCrashes = true;
                } else if (opt.equals("--ignore-native-crashes")) {
                    mIgnoreNativeCrashes = true;
                } else if (opt.equals("--kill-process-after-error")) {
                    mKillProcessAfterError = true;
                } else if (opt.equals("--hprof")) {
                    mGenerateHprof = true;
                } else if (opt.equals("--match-description")) {
                    mMatchDescription = nextOptionData();
                } else if (opt.equals("--pct-touch")) {
                    mFactors[MonkeySourceRandom.FACTOR_TOUCH] =
                            -nextOptionLong("touch events percentage");
                } else if (opt.equals("--pct-motion")) {
                    mFactors[MonkeySourceRandom.FACTOR_MOTION] =
                            -nextOptionLong("motion events percentage");
                } else if (opt.equals("--pct-trackball")) {
                    mFactors[MonkeySourceRandom.FACTOR_TRACKBALL] =
                            -nextOptionLong("trackball events percentage");
                } else if (opt.equals("--pct-rotation")) {
                    mFactors[MonkeySourceRandom.FACTOR_ROTATION] =
                            -nextOptionLong("screen rotation events percentage");
                } else if (opt.equals("--pct-syskeys")) {
                    mFactors[MonkeySourceRandom.FACTOR_SYSOPS] =
                            -nextOptionLong("system (key) operations percentage");
                } else if (opt.equals("--pct-nav")) {
                    mFactors[MonkeySourceRandom.FACTOR_NAV] =
                            -nextOptionLong("nav events percentage");
                } else if (opt.equals("--pct-majornav")) {
                    mFactors[MonkeySourceRandom.FACTOR_MAJORNAV] =
                            -nextOptionLong("major nav events percentage");
                } else if (opt.equals("--pct-appswitch")) {
                    mFactors[MonkeySourceRandom.FACTOR_APPSWITCH] =
                            -nextOptionLong("app switch events percentage");
                } else if (opt.equals("--pct-flip")) {
                    mFactors[MonkeySourceRandom.FACTOR_FLIP] =
                            -nextOptionLong("keyboard flip percentage");
                } else if (opt.equals("--pct-anyevent")) {
                    mFactors[MonkeySourceRandom.FACTOR_ANYTHING] =
                            -nextOptionLong("any events percentage");
                } else if (opt.equals("--pct-pinchzoom")) {
                    mFactors[MonkeySourceRandom.FACTOR_PINCHZOOM] =
                            -nextOptionLong("pinch zoom events percentage");
                } else if (opt.equals("--pct-permission")) {
                    mFactors[MonkeySourceRandom.FACTOR_PERMISSION] =
                            -nextOptionLong("runtime permission toggle events percentage");
                } else if (opt.equals("--pkg-blacklist-file")) {
                    mPkgBlacklistFile = nextOptionData();
                } else if (opt.equals("--pkg-whitelist-file")) {
                    mPkgWhitelistFile = nextOptionData();
                } else if (opt.equals("--throttle")) {
                    mThrottle = nextOptionLong("delay (in milliseconds) to wait between events");
                } else if (opt.equals("--randomize-throttle")) {
                    mRandomizeThrottle = true;
                } else if (opt.equals("--wait-dbg")) {
                    // do nothing - it's caught at the very start of run()
                } else if (opt.equals("--dbg-no-events")) {
                    mSendNoEvents = true;
                } else if (opt.equals("--port")) {
                    mServerPort = (int) nextOptionLong("Server port to listen on for commands");
                } else if (opt.equals("--setup")) {
                    mSetupFileName = nextOptionData();
                } else if (opt.equals("-f")) {
                    mScriptFileNames.add(nextOptionData());
                } else if (opt.equals("--profile-wait")) {
                    mProfileWaitTime = nextOptionLong(
                            "Profile delay (in milliseconds) to wait between user action");
                } else if (opt.equals("--device-sleep-time")) {
                    mDeviceSleepTime = nextOptionLong("Device sleep time(in milliseconds)");
                } else if (opt.equals("--randomize-script")) {
                    mRandomizeScript = true;
                } else if (opt.equals("--script-log")) {
                    mScriptLog = true;
                } else if (opt.equals("--bugreport")) {
                    mRequestBugreport = true;
                } else if (opt.equals("--periodic-bugreport")) {
                    mGetPeriodicBugreport = true;
                    mBugreportFrequency = nextOptionLong("Number of iterations");
                } else if (opt.equals("--permission-target-system")) {
                    mPermissionTargetSystem = true;
                } else if (opt.equals("-h")) {
                    showUsage();
                    return false;
                } else {
                    Logger.err.println("** Error: Unknown option: " + opt);
                    showUsage();
                    return false;
                }
            }
        } catch (RuntimeException ex) {
            Logger.err.println("** Error: " + ex.toString());
            showUsage();
            return false;
        }
    }

    private static boolean loadPackageListFromFile(String fileName, Set<String> list) {
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new FileReader(fileName));
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.length() > 0 && !line.startsWith("#")) {
                    list.add(line);
                }
            }
            return true;
        } catch (IOException e) {
            Logger.err.println("" + e);
            return false;
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (IOException e) {
                    Logger.err.println("" + e);
                }
            }
        }
    }

    private boolean loadPackageLists() {
        Logger.xtcDebug.println("Monkey loadPackageLists begin");
        if ((mPkgWhitelistFile != null || MonkeyUtils.getPackageFilter().hasValidPackages())
                && mPkgBlacklistFile != null) {
            Logger.xtcDebug.println("** Error: you can not specify a package blacklist "
                    + "together with a whitelist or individual packages (via -p).");
            return false;
        }
        Set<String> validPackages = new HashSet<String>();
        if (mPkgWhitelistFile != null
                && !loadPackageListFromFile(mPkgWhitelistFile, validPackages)) {
            Logger.xtcDebug.println("** loadPackageLists from validPackages Error -1-  .");
            return false;
        }
        MonkeyUtils.getPackageFilter().addValidPackages(validPackages);
        Set<String> invalidPackages = new HashSet<String>();
        if (mPkgBlacklistFile == null
                || loadPackageListFromFile(mPkgBlacklistFile, invalidPackages)) {
            MonkeyUtils.getPackageFilter().addInvalidPackages(invalidPackages);
            return true;
        }
        Logger.xtcDebug.println("** loadPackageLists from invalidPackages Error -2-  .");
        return false;
    }

    private boolean getMainApps() {
        try {
            int N = mMainCategories.size();
            for (int i = 0; i < N; i++) {
                Intent intent = new Intent(Intent.ACTION_MAIN);
                String category = mMainCategories.get(i);
                if (category.length() > 0) {
                    intent.addCategory(category);
                }
                List<ResolveInfo> mainApps = mPm.queryIntentActivities(intent, null, 0,
                        UserHandle.myUserId()).getList();
                if (mainApps == null || mainApps.size() == 0) {
                    Logger.err.println("// Warning: no activities found for category "
                            + category);
                    continue;
                }
                if (mVerbose >= 2) {
                    Logger.out.println("// Selecting main activities from category " + category);
                }
                int num = mainApps.size();
                for (int j = 0; j < num; j++) {
                    ResolveInfo r = mainApps.get(j);
                    String packageName = r.activityInfo.applicationInfo.packageName;
                    if (MonkeyUtils.getPackageFilter().checkEnteringPackage(packageName)) {
                        if (mVerbose >= 2) {
                            Logger.out.println("//   + Using main activity " + r.activityInfo.name
                                    + " (from package " + packageName + ")");
                        }
                        mMainApps.add(new ComponentName(packageName, r.activityInfo.name));
                    } else {
                        if (mVerbose >= 3) {
                            Logger.out.println("//   - NOT USING main activity "
                                    + r.activityInfo.name + " (from package " + packageName + ")");
                        }
                    }
                }
            }
        } catch (RemoteException e) {
            Logger.err.println("** Failed talking with package manager!");
            return false;
        }
        if (mMainApps.size() == 0) {
            Logger.out.println("** No activities found to run, monkey aborted.");
            return false;
        }
        return true;
    }

    private boolean getSystemInterfaces() {
        mAm = ActivityManager.getService();
        if (mAm == null) {
            Logger.err.println("** Error: Unable to connect to activity manager; is the system "
                    + "running?");
            return false;
        }
        mWm = IWindowManager.Stub.asInterface(ServiceManager.getService("window"));
        if (mWm == null) {
            Logger.err.println("** Error: Unable to connect to window manager; is the system "
                    + "running?");
            return false;
        }
        mPm = IPackageManager.Stub.asInterface(ServiceManager.getService("package"));
        if (mPm == null) {
            Logger.err.println("** Error: Unable to connect to package manager; is the system "
                    + "running?");
            return false;
        }
        try {
            mAm.setActivityController(new ActivityController(), true);
            mNetworkMonitor.register(mAm);
            return true;
        } catch (RemoteException e) {
            Logger.err.println("** Failed talking with activity manager!");
            return false;
        }
    }

    /**
     * Monitor activity manager callbacks so we can track the current package.
     */
    private class ActivityController extends IActivityController.Stub {
        /**
         * Check whether we should allow the activity to start.
         */
        private boolean isActivityStartingAllowed(Intent intent, String pkg) {
            if (MonkeyUtils.getPackageFilter().checkEnteringPackage(pkg)) {
                return true;
            }

            Set<String> categories = intent.getCategories();
            if (intent.getAction() == Intent.ACTION_MAIN && categories != null
                    && categories.contains(Intent.CATEGORY_HOME)) {
                try {
                    if (pkg.equals(mPm.resolveIntent(intent, intent.getType(), 0,
                            UserHandle.myUserId()).activityInfo.packageName)) {
                        return true;
                    }
                } catch (RemoteException e) {
                    Logger.err.println("** Failed talking with package manager!");
                    return false;
                }
            }
            return false;
        }

        @Override
        public boolean activityResuming(String pkg) {
            StrictMode.ThreadPolicy policy = StrictMode.allowThreadDiskWrites();
            Logger.out.println("    // activityResuming(" + pkg + ")");
            boolean allow = MonkeyUtils.getPackageFilter().checkEnteringPackage(pkg);
            if (!allow && mVerbose > 0) {
                Logger.out.println("    // " + (allow ? "Allowing" : "Rejecting")
                        + " resume of package " + pkg);
            }
            currentPackage = pkg;
            StrictMode.setThreadPolicy(policy);
            return true;
        }

        @Override
        public boolean activityStarting(Intent intent, String pkg) {
            boolean allow = isActivityStartingAllowed(intent, pkg);
            if (mVerbose > 0) {
                StrictMode.ThreadPolicy policy = StrictMode.allowThreadDiskWrites();
                Logger.out.println("    // " + (allow ? "Allowing" : "Rejecting") + " start of "
                        + intent + " in package " + pkg);
                StrictMode.setThreadPolicy(policy);
            }
            currentPackage = pkg;
            currentIntent = intent;
            return allow;
        }

        @Override
        public boolean appCrashed(String processName, int pid, String shortMsg, String longMsg,
                long timeMillis, String stackTrace) {
            StrictMode.ThreadPolicy policy = StrictMode.allowThreadDiskWrites();
            Logger.err.println("// CRASH: " + processName + " (pid " + pid + ")");
            Logger.err.println("// Short Msg: " + shortMsg);
            Logger.err.println("// Long Msg: " + longMsg);
            Logger.err.println("// Build Label: " + Build.FINGERPRINT);
            Logger.err.println("// Build Changelist: " + Build.VERSION.INCREMENTAL);
            Logger.err.println("// Build Time: " + Build.TIME);
            Logger.err.println("// " + stackTrace.replace("\n", "\n// "));
            StrictMode.setThreadPolicy(policy);

            if (mMatchDescription != null
                    && !shortMsg.contains(mMatchDescription)
                    && !longMsg.contains(mMatchDescription)
                    && !stackTrace.contains(mMatchDescription)) {
                return false;
            }

            if (mIgnoreCrashes && !mRequestBugreport) {
                return false;
            }

            synchronized (Monkey.this) {
                if (!mIgnoreCrashes) {
                    mAbort = true;
                }
                if (mRequestBugreport) {
                    mRequestAppCrashBugreport = true;
                    mReportProcessName = processName;
                }
            }
            return !mKillProcessAfterError;
        }

        @Override
        public int appEarlyNotResponding(String processName, int pid, String annotation) {
            Logger.xtcDebug.println("appEarlyNotResponding:: processName=" + processName
                    + ", pid=" + pid + ", annotation=" + annotation);
            return -1;
        }

        @Override
        public int appNotResponding(String processName, int pid, String annotation) {
            StrictMode.ThreadPolicy policy = StrictMode.allowThreadDiskWrites();
            Logger.err.println("// NOT RESPONDING: " + processName + " (pid " + pid + ")");
            Logger.err.println(annotation);
            StrictMode.setThreadPolicy(policy);

            if (mMatchDescription == null || annotation.contains(mMatchDescription)) {
                synchronized (Monkey.this) {
                    mRequestAnrTraces = true;
                    mRequestDumpsysMemInfo = true;
                    mRequestProcRank = true;
                    if (mRequestBugreport) {
                        mRequestAnrBugreport = true;
                        mReportProcessName = processName;
                    }
                }
                if (!mIgnoreTimeouts) {
                    synchronized (Monkey.this) {
                        mAbort = true;
                    }
                }
            }
            return mKillProcessAfterError ? -1 : 1;
        }

        @Override
        public int systemNotResponding(String message) {
            StrictMode.ThreadPolicy policy = StrictMode.allowThreadDiskWrites();
            Logger.err.println("// WATCHDOG: " + message);
            StrictMode.setThreadPolicy(policy);
            synchronized (Monkey.this) {
                if (mMatchDescription == null || message.contains(mMatchDescription)) {
                    if (!mIgnoreCrashes) {
                        mAbort = true;
                    }
                    if (mRequestBugreport) {
                        mRequestWatchdogBugreport = true;
                    }
                }
                mWatchdogWaiting = true;
            }
            synchronized (Monkey.this) {
                while (mWatchdogWaiting) {
                    try {
                        Monkey.this.wait();
                    } catch (InterruptedException e) {
                        // ignore
                    }
                }
            }
            return mKillProcessAfterError ? -1 : 1;
        }
    }

    /**
     * Report a bugreport-style command.
     */
    private void commandLineReport(String reportName, String command) {
        Logger.err.println(reportName + ":");
        Runtime.getRuntime();
        try {
            Process p = Runtime.getRuntime().exec(command);
            BufferedWriter log = mRequestBugreport
                    ? new BufferedWriter(new FileWriter(
                            new File(Environment.getLegacyExternalStorageDirectory(), reportName),
                            true))
                    : null;
            BufferedReader in = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line;
            while ((line = in.readLine()) != null) {
                if (mRequestBugreport) {
                    try {
                        log.write(line);
                        log.write("\n");
                    } catch (IOException e) {
                        // stop writing and drain the output
                        while (in.readLine() != null) {
                        }
                        Logger.err.println(e.toString());
                        Logger.err.println("// " + reportName + " status was " + p.waitFor());
                        if (log != null) {
                            log.close();
                        }
                    }
                } else {
                    Logger.err.println(line);
                }
            }
            Logger.err.println("// " + reportName + " status was " + p.waitFor());
            if (log != null) {
                log.close();
            }
        } catch (Exception e) {
            Logger.err.println("// Exception from " + reportName + ":");
            Logger.err.println(e.toString());
        }
    }

    private void getBugreport(String reportName) {
        commandLineReport((reportName + MonkeyUtils.toCalendarTime(System.currentTimeMillis()))
                .replaceAll("[ ,:]", "_") + ".txt", "bugreport");
    }

    private void reportAnrTraces() {
        try {
            Thread.sleep(5000);
        } catch (InterruptedException e) {
        }
        File[] anrFiles = new File("/data/anr/").listFiles();
        if (anrFiles != null) {
            long date = 0;
            File anrFile = null;
            for (File f : anrFiles) {
                if (f.lastModified() > date) {
                    date = f.lastModified();
                    anrFile = f;
                }
            }
            if (anrFile != null) {
                commandLineReport("anr traces", "cat " + anrFile.getAbsolutePath());
            }
        }
    }

    private void reportDumpsysMemInfo() {
        commandLineReport("meminfo", "dumpsys meminfo");
    }

    private void reportProcRank() {
        commandLineReport("procrank", "procrank");
    }

    private boolean checkInternalConfiguration() {
        return true;
    }

    private boolean checkNativeCrashes() {
        boolean found = false;
        String[] tombstones = TOMBSTONES_PATH.list();
        if (tombstones == null || tombstones.length == 0) {
            mTombstones = null;
            return false;
        }
        HashSet<Long> newTombstones = new HashSet<Long>();
        for (String t : tombstones) {
            if (t.startsWith(TOMBSTONE_PREFIX)) {
                File f = new File(TOMBSTONES_PATH, t);
                newTombstones.add(f.lastModified());
                if (mTombstones == null || !mTombstones.contains(f.lastModified())) {
                    found = true;
                    Logger.out.println("** New tombstone found: " + f.getAbsolutePath()
                            + ", size: " + f.length());
                }
            }
        }
        mTombstones = newTombstones;
        return found;
    }

    private void signalPersistentProcesses() {
        try {
            mAm.signalPersistentProcesses(10);
            synchronized (this) {
                wait(2000);
            }
        } catch (RemoteException e) {
            Logger.err.println("** Failed talking with activity manager!");
        } catch (InterruptedException e) {
        }
    }

    private void writeScriptLog(int count) {
        try {
            BufferedWriter writer = new BufferedWriter(new FileWriter(
                    new File(Environment.getLegacyExternalStorageDirectory(), "scriptlog.txt"),
                    true));
            writer.write("iteration: " + count + " time: "
                    + MonkeyUtils.toCalendarTime(System.currentTimeMillis()) + "\n");
            writer.close();
        } catch (IOException e) {
            Logger.err.println(e.toString());
        }
    }

    private int runMonkeyCycles() {
        int eventCounter = 0;
        int cycleCounter = 0;
        long currentTime = 0;
        boolean requestAnrTraces = false;
        boolean requestDumpsysMemInfo = false;
        boolean abort = false;
        boolean done = false;

        while (!done) {
            try {
                if (cycleCounter >= mCount) {
                    break;
                }
                synchronized (this) {
                    if (mRequestProcRank) {
                        reportProcRank();
                        mRequestProcRank = false;
                    }
                    if (mRequestAnrTraces) {
                        mRequestAnrTraces = false;
                        requestAnrTraces = true;
                    }
                    if (mRequestAnrBugreport) {
                        getBugreport("anr_" + mReportProcessName + "_");
                        mRequestAnrBugreport = false;
                    }
                    if (mRequestWatchdogBugreport) {
                        Logger.xtcDebug.println("Print the watchdog report");
                        getBugreport("anr_watchdog_");
                        mRequestWatchdogBugreport = false;
                    }
                    if (mRequestAppCrashBugreport) {
                        getBugreport("app_crash" + mReportProcessName + "_");
                        mRequestAppCrashBugreport = false;
                    }
                    if (mRequestPeriodicBugreport) {
                        getBugreport("Bugreport_");
                        mRequestPeriodicBugreport = false;
                    }
                    if (mRequestDumpsysMemInfo) {
                        mRequestDumpsysMemInfo = false;
                        requestDumpsysMemInfo = true;
                    }
                    if (mMonitorNativeCrashes && checkNativeCrashes() && eventCounter > 0) {
                        Logger.xtcDebug.println("** New native crash detected.");
                        if (mRequestBugreport) {
                            getBugreport("native_crash_");
                        }
                        mAbort = (mAbort || !mIgnoreNativeCrashes) ? true : mKillProcessAfterError;
                    }
                    if (mAbort) {
                        abort = true;
                    }
                    if (mWatchdogWaiting) {
                        mWatchdogWaiting = false;
                        notifyAll();
                    }
                }
                if (requestAnrTraces) {
                    requestAnrTraces = false;
                    reportAnrTraces();
                }
                if (requestDumpsysMemInfo) {
                    requestDumpsysMemInfo = false;
                    reportDumpsysMemInfo();
                }
                if (!abort) {
                    long now = System.currentTimeMillis();
                    if (mRunTime > 0 && mStartTime > 0) {
                        currentTime = mStartTime + (mRunTime * 1000);
                    }
                    if (currentTime > 0 && currentTime - now <= 0) {
                        Logger.xtcDebug.println("** Monkey test Done! stop time: "
                                + (currentTime / 1000));
                        break;
                    }
                    if (!mSendNoEvents) {
                        if (mVerbose > 0 && eventCounter % 100 == 0 && eventCounter != 0) {
                            Logger.xtcDebug.println("    //[calendar_time:"
                                    + MonkeyUtils.toCalendarTime(System.currentTimeMillis())
                                    + " system_uptime:" + SystemClock.elapsedRealtime() + "]");
                            Logger.xtcDebug.println("    // Sending event #" + eventCounter);
                        }
                        MonkeyEvent ev = mEventSource.getNextEvent();
                        if (ev == null) {
                            if (mCountEvents) {
                                break;
                            }
                            cycleCounter++;
                            writeScriptLog(cycleCounter);
                            if (mGetPeriodicBugreport
                                    && ((long) cycleCounter) % mBugreportFrequency == 0) {
                                mRequestPeriodicBugreport = true;
                            }
                        } else {
                            int injectCode = ev.injectEvent(mWm, mAm, mVerbose);
                            if (injectCode == MonkeyEvent.INJECT_FAIL) {
                                Logger.xtcDebug.println("    // Injection Failed");
                                if (ev instanceof MonkeyKeyEvent) {
                                    mDroppedKeyEvents++;
                                } else if (ev instanceof MonkeyMotionEvent) {
                                    mDroppedPointerEvents++;
                                } else if (ev instanceof MonkeyFlipEvent) {
                                    mDroppedFlipEvents++;
                                } else if (ev instanceof MonkeyRotationEvent) {
                                    mDroppedRotationEvents++;
                                }
                            } else if (injectCode == MonkeyEvent.INJECT_ERROR_REMOTE_EXCEPTION) {
                                done = true;
                                Logger.xtcDebug.println(
                                        "** Error: RemoteException while injecting event.");
                            } else if (injectCode
                                    == MonkeyEvent.INJECT_ERROR_SECURITY_EXCEPTION) {
                                done = !mIgnoreSecurityExceptions;
                                if (done) {
                                    Logger.xtcDebug.println(
                                            "** Error: SecurityException while injecting event.");
                                }
                            }
                            if (!(ev instanceof MonkeyThrottleEvent)) {
                                eventCounter++;
                                if (mCountEvents) {
                                    cycleCounter++;
                                }
                            }
                        }
                    } else {
                        eventCounter++;
                        cycleCounter++;
                    }
                } else {
                    Logger.xtcDebug.println("** Monkey aborted due to error.");
                    Logger.xtcDebug.println("Events injected: " + eventCounter);
                    return eventCounter;
                }
            } catch (RuntimeException e) {
                Logger.xtcError("** Error: A RuntimeException occurred:", e);
            }
        }
        Logger.xtcDebug.println("Events injected: " + eventCounter);
        return eventCounter;
    }

    private int run(String[] args) {
        for (String arg : args) {
            if ("--wait-dbg".equals(arg)) {
                Debug.waitForDebugger();
            }
        }
        mMonkeyStatus = SystemProperties.get("log.tag.monkeyrun", "false");
        if ("false".equals(mMonkeyStatus)) {
            SystemProperties.set("log.tag.monkeyrun", "true");
        }
        mVerbose = 0;
        mCount = 1000;
        mSeed = 0;
        mThrottle = 0;
        mStartTime = 0;
        mRunTime = 0;
        mArgs = args;

        for (String arg : args) {
            Logger.xtcDebug.println(" arg: \"" + arg + "\"");
        }
        mNextArg = 0;

        for (int i = 0; i < MonkeySourceRandom.FACTORZ_COUNT; i++) {
            mFactors[i] = 1.0f;
        }

        if (!processOptions()) {
            Logger.xtcDebug.println("Monkey processOptions");
            return -1;
        }
        if (!loadPackageLists()) {
            Logger.xtcDebug.println("Monkey loadPackageLists");
            return -1;
        }

        // set up the main apps
        if (mMainCategories.size() == 0) {
            mMainCategories.add(Intent.CATEGORY_LAUNCHER);
            mMainCategories.add(Intent.CATEGORY_MONKEY);
        }

        if (mSeed == 0) {
            mSeed = System.currentTimeMillis() + System.identityHashCode(this);
        }

        if (mStartTime == 0) {
            mStartTime = System.currentTimeMillis();
            Logger.xtcDebug.println(":Monkey: mStartTime=" + mStartTime + "mRunTime = "
                    + mRunTime + "(s)\n");
        }

        if (mVerbose > 0) {
            Logger.xtcDebug.println(":Monkey: seed=" + mSeed + " count=" + mCount);
            MonkeyUtils.getPackageFilter().dump();
            if (mMainCategories.size() != 0) {
                Iterator<String> it = mMainCategories.iterator();
                while (it.hasNext()) {
                    Logger.xtcDebug.println(":IncludeCategory: " + it.next());
                }
            }
        }

        if (!checkInternalConfiguration()) {
            return -2;
        }
        if (!getSystemInterfaces()) {
            return -3;
        }
        if (!getMainApps()) {
            return -4;
        }

        mRandom = new Random(mSeed);

        if (mScriptFileNames != null && mScriptFileNames.size() == 1) {
            // script mode
            mEventSource = new MonkeySourceScript(mRandom, mScriptFileNames.get(0), mThrottle,
                    mRandomizeThrottle, mProfileWaitTime, mDeviceSleepTime);
            mEventSource.setVerbose(mVerbose);
            mCountEvents = false;
        } else if (mScriptFileNames != null && mScriptFileNames.size() > 1) {
            if (mSetupFileName != null) {
                mEventSource = new MonkeySourceRandomScript(mSetupFileName, mScriptFileNames,
                        mThrottle, mRandomizeThrottle, mRandom, mProfileWaitTime,
                        mDeviceSleepTime, mRandomizeScript);
                mCount++;
            } else {
                mEventSource = new MonkeySourceRandomScript(mScriptFileNames, mThrottle,
                        mRandomizeThrottle, mRandom, mProfileWaitTime, mDeviceSleepTime,
                        mRandomizeScript);
            }
            mEventSource.setVerbose(mVerbose);
            mCountEvents = false;
        } else if (mServerPort != -1) {
            try {
                mEventSource = new MonkeySourceNetwork(mServerPort);
            } catch (IOException e) {
                Logger.xtcDebug.println("Error binding to network socket.");
                return -5;
            }
            mCount = Integer.MAX_VALUE;
        } else {
            // random source by default
            if (mVerbose >= 2) {
                Logger.xtcDebug.println("// Seeded: " + mSeed);
            }
            mEventSource = new MonkeySourceRandom(mRandom, mMainApps, mThrottle,
                    mRandomizeThrottle, mPermissionTargetSystem);
            mEventSource.setVerbose(mVerbose);
            for (int i = 0; i < MonkeySourceRandom.FACTORZ_COUNT; i++) {
                if (mFactors[i] <= 0.0f) {
                    ((MonkeySourceRandom) mEventSource).setFactors(i, mFactors[i]);
                }
            }
            ((MonkeySourceRandom) mEventSource).generateActivity();
        }

        if (!mEventSource.validate()) {
            return -5;
        }

        if (mGenerateHprof) {
            signalPersistentProcesses();
        }

        mNetworkMonitor.start();
        try {
            int count = runMonkeyCycles();
            // Just in case we abort for some reason, remove any rotation that we set.
            new MonkeyRotationEvent(0, false).injectEvent(mWm, mAm, mVerbose);
            mNetworkMonitor.stop();

            synchronized (this) {
                if (mRequestAnrTraces) {
                    reportAnrTraces();
                    mRequestAnrTraces = false;
                }
                if (mRequestAnrBugreport) {
                    Logger.xtcDebug.println("Print the anr report");
                    getBugreport("anr_" + mReportProcessName + "_");
                    mRequestAnrBugreport = false;
                }
                if (mRequestWatchdogBugreport) {
                    Logger.xtcDebug.println("Print the watchdog report");
                    getBugreport("anr_watchdog_");
                    mRequestWatchdogBugreport = false;
                }
                if (mRequestAppCrashBugreport) {
                    getBugreport("app_crash" + mReportProcessName + "_");
                    mRequestAppCrashBugreport = false;
                }
                if (mRequestDumpsysMemInfo) {
                    reportDumpsysMemInfo();
                    mRequestDumpsysMemInfo = false;
                }
                if (mRequestPeriodicBugreport) {
                    getBugreport("Bugreport_");
                    mRequestPeriodicBugreport = false;
                }
                if (mWatchdogWaiting) {
                    mWatchdogWaiting = false;
                    notifyAll();
                }
            }

            if (mGenerateHprof) {
                signalPersistentProcesses();
                if (mVerbose > 0) {
                    Logger.xtcDebug.println("// Generated profiling reports in /data/misc");
                }
            }

            try {
                mAm.setActivityController(null, true);
                mNetworkMonitor.unregister(mAm);
            } catch (RemoteException e) {
                // just in case this was latent (before the run), make sure the
                // return value is sane
                if (count >= mCount) {
                    count = mCount - 1;
                }
            }

            if (mVerbose > 0) {
                Logger.xtcDebug.println(":Dropped: keys=" + mDroppedKeyEvents
                        + " pointers=" + mDroppedPointerEvents
                        + " trackballs=" + mDroppedTrackballEvents
                        + " flips=" + mDroppedFlipEvents
                        + " rotations=" + mDroppedRotationEvents);
            }

            mNetworkMonitor.dump();

            if (count < mCount - 1) {
                Logger.xtcDebug.println("** System appears to have crashed at event " + count
                        + " of " + mCount + " using seed " + mSeed);
                return count;
            } else {
                if (mVerbose > 0) {
                    Logger.xtcDebug.println("// Monkey finished");
                }
                return 0;
            }
        } catch (Throwable th) {
            new MonkeyRotationEvent(0, false).injectEvent(mWm, mAm, mVerbose);
            throw th;
        }
    }

    public static void main(String[] args) {
        // Set the process name showing in "ps" or "top"
        android.os.Process.setArgV0("com.android.commands.monkey");

        Logger.xtcDebug.println("args: " + Arrays.toString(args));
        int resultCode = new Monkey().run(args);
        Logger.xtcDebug.println("Monkey exit!!! event=" + resultCode);
        if ("true".equals(SystemProperties.get("log.tag.monkeyrun", "false"))) {
            SystemProperties.set("log.tag.monkeyrun", "false");
        }
        System.exit(resultCode);
    }

    private void showUsage() {
        StringBuffer usage = new StringBuffer();
        usage.append("usage: monkey [-p ALLOWED_PACKAGE [-p ALLOWED_PACKAGE] ...]\n");
        usage.append("              [-c MAIN_CATEGORY [-c MAIN_CATEGORY] ...]\n");
        usage.append("              [--ignore-crashes] [--ignore-timeouts]\n");
        usage.append("              [--ignore-security-exceptions]\n");
        usage.append("              [--monitor-native-crashes] [--ignore-native-crashes]\n");
        usage.append("              [--kill-process-after-error] [--hprof]\n");
        usage.append("              [--match-description TEXT]\n");
        usage.append("              [--pct-touch PERCENT] [--pct-motion PERCENT]\n");
        usage.append("              [--pct-trackball PERCENT] [--pct-syskeys PERCENT]\n");
        usage.append("              [--pct-nav PERCENT] [--pct-majornav PERCENT]\n");
        usage.append("              [--pct-appswitch PERCENT] [--pct-flip PERCENT]\n");
        usage.append("              [--pct-anyevent PERCENT] [--pct-pinchzoom PERCENT]\n");
        usage.append("              [--pct-permission PERCENT]\n");
        usage.append("              [--pkg-blacklist-file PACKAGE_BLACKLIST_FILE]\n");
        usage.append("              [--pkg-whitelist-file PACKAGE_WHITELIST_FILE]\n");
        usage.append("              [--wait-dbg] [--dbg-no-events]\n");
        usage.append("              [--setup scriptfile] [-f scriptfile [-f scriptfile] ...]\n");
        usage.append("              [--port port]\n");
        usage.append("              [-s SEED] [-v [-v] ...]\n");
        usage.append("              [-t time] seconds\n");
        usage.append("              [--throttle MILLISEC] [--randomize-throttle]\n");
        usage.append("              [--profile-wait MILLISEC]\n");
        usage.append("              [--device-sleep-time MILLISEC]\n");
        usage.append("              [--randomize-script]\n");
        usage.append("              [--script-log]\n");
        usage.append("              [--bugreport]\n");
        usage.append("              [--periodic-bugreport]\n");
        usage.append("              [--permission-target-system]\n");
        usage.append("              COUNT\n");
        Logger.err.println(usage.toString());
    }
}
