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
import android.view.IWindowManager;

/**
 * monkey command event
 */
public class MonkeyCommandEvent extends MonkeyEvent {

    private String mCmd;

    public MonkeyCommandEvent(String cmd) {
        super(MonkeyEvent.EVENT_TYPE_ACTIVITY);
        mCmd = cmd;
    }

    @Override
    public int injectEvent(IWindowManager iwm, IActivityManager iam, int verbose) {
        if (mCmd != null) {
            // Execute the shell command
            try {
                Logger.err.println("// Shell command " + mCmd + " status was "
                        + Runtime.getRuntime().exec(mCmd).waitFor());
            } catch (Exception e) {
                Logger.err.println("// Exception from " + mCmd + ":");
                Logger.err.println(e.toString());
            }
        }
        return MonkeyEvent.INJECT_SUCCESS;
    }
}
