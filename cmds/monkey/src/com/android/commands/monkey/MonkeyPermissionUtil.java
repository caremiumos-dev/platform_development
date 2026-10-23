/*
 * Copyright (C) 2015 The Android Open Source Project
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

import android.content.pm.IPackageManager;
import android.content.pm.PackageInfo;
import android.content.pm.PermissionInfo;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.UserHandle;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Utility class that selects packages and runtime permissions that the monkey
 * can toggle during a run.
 */
public class MonkeyPermissionUtil {

    private static final String[] MODERN_PERMISSION_GROUPS = {
        "android.permission-group.CALENDAR",
        "android.permission-group.CAMERA",
        "android.permission-group.CONTACTS",
        "android.permission-group.LOCATION",
        "android.permission-group.SENSORS",
        "android.permission-group.SMS",
        "android.permission-group.PHONE",
        "android.permission-group.MICROPHONE",
        "android.permission-group.STORAGE"
    };

    private static final String PERMISSION_GROUP_PREFIX = "android.permission-group.";
    private static final String PERMISSION_PREFIX = "android.permission.";

    private IPackageManager mPm = IPackageManager.Stub.asInterface(
            ServiceManager.getService("package"));
    private Map<String, List<PermissionInfo>> mPermissionMap;
    private boolean mTargetSystemPackages;
    private List<String> mTargetedPackages;

    private static boolean isModernPermissionGroup(String name) {
        for (String group : MODERN_PERMISSION_GROUPS) {
            if (group.equals(name)) {
                return true;
            }
        }
        return false;
    }

    private boolean shouldTargetPackage(PackageInfo info) {
        if (MonkeyUtils.getPackageFilter().checkEnteringPackage(info.packageName)) {
            return true;
        }
        if (mTargetSystemPackages) {
            // If this is a system package, and we haven't explicitly listed it as
            // invalid, target it.
            if (!MonkeyUtils.getPackageFilter().isPackageInvalid(info.packageName)
                    && (info.applicationInfo.flags & 1) != 0) {
                return true;
            }
        }
        return false;
    }

    private boolean shouldTargetPermission(String pkg, PermissionInfo perm)
            throws RemoteException {
        int flags = mPm.getPermissionFlags(perm.name, pkg, UserHandle.myUserId());
        if (perm.group != null && perm.protectionLevel == 1 && (flags & 20) == 0) {
            return isModernPermissionGroup(perm.group);
        }
        return false;
    }

    public void dump() {
        Logger.out.println("// Targeted packages and permissions:");
        for (Map.Entry<String, List<PermissionInfo>> e : mPermissionMap.entrySet()) {
            Logger.out.println(String.format("//  + Using %s", e.getKey()));
            for (PermissionInfo p : e.getValue()) {
                String name = p.name;
                if (name != null && name.startsWith(PERMISSION_PREFIX)) {
                    name = name.substring(PERMISSION_PREFIX.length());
                }
                String group = p.group;
                if (group != null && group.startsWith(PERMISSION_GROUP_PREFIX)) {
                    group = group.substring(PERMISSION_GROUP_PREFIX.length());
                }
                Logger.out.println(String.format("//    Permission: %s [%s]", name, group));
            }
        }
    }

    public MonkeyPermissionEvent generateRandomPermissionEvent(Random random) {
        String pkg = mTargetedPackages.get(random.nextInt(mTargetedPackages.size()));
        List<PermissionInfo> infos = mPermissionMap.get(pkg);
        return new MonkeyPermissionEvent(pkg, infos.get(random.nextInt(infos.size())));
    }

    public boolean populatePermissionsMapping() {
        mPermissionMap = new HashMap<String, List<PermissionInfo>>();
        try {
            List<PackageInfo> packageInfos = mPm.getInstalledPackages(4096,
                    UserHandle.myUserId()).getList();
            for (PackageInfo info : packageInfos) {
                if (!shouldTargetPackage(info)) {
                    continue;
                }
                List<PermissionInfo> permissions = new ArrayList<PermissionInfo>();
                if (info.applicationInfo.targetSdkVersion > 22 && info.requestedPermissions != null) {
                    for (String perm : info.requestedPermissions) {
                        PermissionInfo p = mPm.getPermissionInfo(perm, "shell", 0);
                        if (p != null && shouldTargetPermission(info.packageName, p)) {
                            permissions.add(p);
                        }
                    }
                    if (!permissions.isEmpty()) {
                        mPermissionMap.put(info.packageName, permissions);
                    }
                }
            }
            if (mPermissionMap.isEmpty()) {
                return true;
            }
            mTargetedPackages = new ArrayList<String>(mPermissionMap.keySet());
            return true;
        } catch (RemoteException e) {
            Logger.err.println("** Failed talking with package manager!");
            return false;
        }
    }

    public void setTargetSystemPackages(boolean target) {
        mTargetSystemPackages = target;
    }
}
