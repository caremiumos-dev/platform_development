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
import android.content.ComponentName;
import android.content.Intent;
import android.os.Bundle;
import android.os.RemoteException;
import android.view.IWindowManager;

/**
 * monkey activity event
 */
public class MonkeyActivityEvent extends MonkeyEvent {
    private ComponentName mApp;
    long mAlarmTime = 0;

    public MonkeyActivityEvent(ComponentName app) {
        super(MonkeyEvent.EVENT_TYPE_ACTIVITY);
        mApp = app;
    }

    public MonkeyActivityEvent(ComponentName app, long arg) {
        super(MonkeyEvent.EVENT_TYPE_ACTIVITY);
        mApp = app;
        mAlarmTime = arg;
    }

    private Intent getEvent() {
        Intent intent = new Intent(Intent.ACTION_MAIN);
        intent.addCategory(Intent.CATEGORY_LAUNCHER);
        intent.setComponent(mApp);
        intent.addFlags(270532608);
        return intent;
    }

    @Override
    public int injectEvent(IWindowManager iwm, IActivityManager iam, int verbose) {
        Intent intent = getEvent();
        if (verbose > 0) {
            Logger.out.println(":Switch: " + intent.toUri(0));
        }

        if (mAlarmTime != 0) {
            Bundle args = new Bundle();
            args.putLong("alarmTime", mAlarmTime);
            intent.putExtras(args);
        }

        try {
            try {
                String packageName = intent.getComponent().getPackageName();
                String className = intent.getComponent().getClassName();
                // 厂商限制：禁止 monkey 通过随机事件拉起自家启动器
                // com.xtc.i3launcher 的 HomeActivity，从而阻止启动器被压测启动。
                // 这种对特定组件做硬编码拦截、限制第三方/自动化测试自由的做法，
                // 属于典型的厂商信任模型滥用，此处仅忠实保留其行为。
                if ("com.xtc.i3launcher.module.home.view.activity.HomeActivity"
                        .equals(className)) {
                    Logger.xtcDebug.println("Warnning:Switch:  forbid start launcher");
                    return MonkeyEvent.INJECT_FAIL;
                }
                Logger.xtcDebug.println("Monkey startActivity: packageName=" + packageName
                        + ", activityClassName=" + className);
                iam.startActivity(null, null, intent, null, null, null, 0, 0, null, null);
                return MonkeyEvent.INJECT_SUCCESS;
            } catch (RemoteException e) {
                Logger.xtcError("** Failed talking with activity manager : ", e);
                return MonkeyEvent.INJECT_SUCCESS;
            } catch (SecurityException e) {
                if (verbose <= 0) {
                    return MonkeyEvent.INJECT_ERROR_SECURITY_EXCEPTION;
                }
                Logger.xtcDebug.println("** Permissions error starting activity "
                        + intent.toUri(0));
                return MonkeyEvent.INJECT_ERROR_SECURITY_EXCEPTION;
            }
        } catch (RuntimeException e) {
            Logger.xtcError("** MonkeyActivityEvent Error: A RuntimeException occurred:", e);
            return MonkeyEvent.INJECT_SUCCESS;
        }
    }
}
