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

import java.util.ArrayList;
import java.util.Random;

/**
 * monkey source that runs a random script
 */
public class MonkeySourceRandomScript implements MonkeyEventSource {
    private MonkeySourceScript mCurrentSource;
    private ArrayList<MonkeySourceScript> mScriptSources;
    private MonkeySourceScript mSetupSource;
    private Random mRandom;
    private int mVerbose = 0;
    private boolean mRandomizeScript = false;
    private int mScriptCount = 0;

    public MonkeySourceRandomScript(String setupFileName, ArrayList<String> scriptFileNames,
            long throttle, boolean randomizeThrottle, Random random, long profileWaitTime,
            long deviceSleepTime, boolean randomizeScript) {
        if (setupFileName != null) {
            mSetupSource = new MonkeySourceScript(random, setupFileName, throttle,
                    randomizeThrottle, profileWaitTime, deviceSleepTime);
            mCurrentSource = mSetupSource;
        }

        mScriptSources = new ArrayList<MonkeySourceScript>();
        for (String fileName : scriptFileNames) {
            mScriptSources.add(new MonkeySourceScript(random, fileName, throttle,
                    randomizeThrottle, profileWaitTime, deviceSleepTime));
        }

        mRandom = random;
        mRandomizeScript = randomizeScript;
    }

    public MonkeySourceRandomScript(ArrayList<String> scriptFileNames, long throttle,
            boolean randomizeThrottle, Random random, long profileWaitTime,
            long deviceSleepTime, boolean randomizeScript) {
        this(null, scriptFileNames, throttle, randomizeThrottle, random, profileWaitTime,
                deviceSleepTime, randomizeScript);
    }

    @Override
    public MonkeyEvent getNextEvent() {
        if (mCurrentSource == null) {
            int numSources = mScriptSources.size();
            if (numSources == 1) {
                mCurrentSource = mScriptSources.get(0);
            } else if (numSources > 1) {
                if (mRandomizeScript) {
                    mCurrentSource = mScriptSources.get(mRandom.nextInt(numSources));
                } else {
                    mCurrentSource = mScriptSources.get(mScriptCount % numSources);
                    mScriptCount++;
                }
            }
        }

        if (mCurrentSource != null) {
            MonkeyEvent nextEvent = mCurrentSource.getNextEvent();
            if (nextEvent == null) {
                mCurrentSource = null;
            }
            return nextEvent;
        }
        return null;
    }

    @Override
    public void setVerbose(int verbose) {
        mVerbose = verbose;

        if (mSetupSource != null) {
            mSetupSource.setVerbose(verbose);
        }

        for (MonkeySourceScript source : mScriptSources) {
            source.setVerbose(verbose);
        }
    }

    @Override
    public boolean validate() {
        if (mSetupSource != null && !mSetupSource.validate()) {
            return false;
        }

        for (MonkeySourceScript source : mScriptSources) {
            if (!source.validate()) {
                return false;
            }
        }

        return true;
    }
}
