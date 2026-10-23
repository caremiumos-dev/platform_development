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

import android.app.UiAutomation;
import android.app.UiAutomationConnection;
import android.content.pm.IPackageManager;
import android.graphics.Rect;
import android.os.HandlerThread;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.UserHandle;
import android.view.accessibility.AccessibilityInteractionClient;
import android.view.accessibility.AccessibilityNodeInfo;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import dalvik.system.DexClassLoader;

/**
 * Class for querying the view hierarchy using the accessibility framework.
 */
public class MonkeySourceNetworkViews {

    private static final String NO_ACCESSIBILITY_EVENT = "No accessibility event has occured yet";
    private static final String NO_NODE = "Node with given ID does not exist";
    private static final String NO_CONNECTION = "Failed to connect to AccessibilityService, "
            + "try restarting Monkey";
    private static final String REMOTE_ERROR = "Unable to retrieve application info from "
            + "PackageManager";
    private static final String CLASS_NOT_FOUND = "Error retrieving class information";
    private static final String HANDLER_THREAD_NAME = "UiAutomationHandlerThread";

    private static final Map<String, ViewIntrospectionCommand> COMMAND_MAP =
            new HashMap<String, ViewIntrospectionCommand>();

    private static final Map<String, Class<?>> sClassMap = new HashMap<String, Class<?>>();

    private static final HandlerThread sHandlerThread = new HandlerThread(HANDLER_THREAD_NAME);

    protected static UiAutomation sUiTestAutomationBridge;

    private static IPackageManager sPm = IPackageManager.Stub.asInterface(
            ServiceManager.getService("package"));

    private interface ViewIntrospectionCommand {
        MonkeySourceNetwork.MonkeyCommandReturn query(AccessibilityNodeInfo node,
                List<String> args);
    }

    public static class GetAccessibilityIds implements ViewIntrospectionCommand {
        @Override
        public MonkeySourceNetwork.MonkeyCommandReturn query(AccessibilityNodeInfo node,
                List<String> args) {
            if (args.size() != 0) {
                return MonkeySourceNetwork.EARG;
            }

            try {
                Class<?> c = node.getClass();
                Field field = c.getDeclaredField("mAccessibilityViewId");
                field.setAccessible(true);
                int viewId = (Integer) field.get(node);
                return new MonkeySourceNetwork.MonkeyCommandReturn(true,
                        node.getWindowId() + " " + viewId);
            } catch (NoSuchFieldException e) {
                return new MonkeySourceNetwork.MonkeyCommandReturn(false, NO_NODE);
            } catch (IllegalAccessException e) {
                return new MonkeySourceNetwork.MonkeyCommandReturn(false, "Access exception");
            }
        }
    }

    public static class GetChildren implements ViewIntrospectionCommand {
        @Override
        public MonkeySourceNetwork.MonkeyCommandReturn query(AccessibilityNodeInfo node,
                List<String> args) {
            if (args.size() != 0) {
                return MonkeySourceNetwork.EARG;
            }

            GetAccessibilityIds ids = new GetAccessibilityIds();
            List<String> empty = new ArrayList<String>();
            StringBuilder ret = new StringBuilder();

            for (int i = 0; i < node.getChildCount(); i++) {
                MonkeySourceNetwork.MonkeyCommandReturn result = ids.query(node.getChild(i),
                        empty);
                if (!result.wasSuccessful()) {
                    return result;
                }
                ret.append(result.getMessage()).append(" ");
            }

            return new MonkeySourceNetwork.MonkeyCommandReturn(true, ret.toString());
        }
    }

    public static class GetParent implements ViewIntrospectionCommand {
        @Override
        public MonkeySourceNetwork.MonkeyCommandReturn query(AccessibilityNodeInfo node,
                List<String> args) {
            if (args.size() != 0) {
                return MonkeySourceNetwork.EARG;
            }

            AccessibilityNodeInfo parent = node.getParent();
            if (parent == null) {
                return new MonkeySourceNetwork.MonkeyCommandReturn(false,
                        "Given node has no parent");
            }
            return new GetAccessibilityIds().query(parent, new ArrayList<String>());
        }
    }

    public static class GetFocused implements ViewIntrospectionCommand {
        @Override
        public MonkeySourceNetwork.MonkeyCommandReturn query(AccessibilityNodeInfo node,
                List<String> args) {
            if (args.size() != 0) {
                return MonkeySourceNetwork.EARG;
            }
            return new MonkeySourceNetwork.MonkeyCommandReturn(true,
                    Boolean.toString(node.isFocused()));
        }
    }

    public static class SetFocused implements ViewIntrospectionCommand {
        @Override
        public MonkeySourceNetwork.MonkeyCommandReturn query(AccessibilityNodeInfo node,
                List<String> args) {
            if (args.size() != 1) {
                return MonkeySourceNetwork.EARG;
            }

            boolean ret;
            if (Boolean.valueOf(args.get(0)).booleanValue()) {
                ret = node.performAction(1 /* ACTION_FOCUS */);
            } else {
                ret = node.performAction(2 /* ACTION_CLEAR_FOCUS */);
            }
            return new MonkeySourceNetwork.MonkeyCommandReturn(ret);
        }
    }

    public static class GetSelected implements ViewIntrospectionCommand {
        @Override
        public MonkeySourceNetwork.MonkeyCommandReturn query(AccessibilityNodeInfo node,
                List<String> args) {
            if (args.size() != 0) {
                return MonkeySourceNetwork.EARG;
            }
            return new MonkeySourceNetwork.MonkeyCommandReturn(true,
                    Boolean.toString(node.isSelected()));
        }
    }

    public static class SetSelected implements ViewIntrospectionCommand {
        @Override
        public MonkeySourceNetwork.MonkeyCommandReturn query(AccessibilityNodeInfo node,
                List<String> args) {
            if (args.size() != 1) {
                return MonkeySourceNetwork.EARG;
            }

            boolean ret;
            if (Boolean.valueOf(args.get(0)).booleanValue()) {
                ret = node.performAction(4 /* ACTION_SELECT */);
            } else {
                ret = node.performAction(8 /* ACTION_CLEAR_SELECTION */);
            }
            return new MonkeySourceNetwork.MonkeyCommandReturn(ret);
        }
    }

    public static class GetEnabled implements ViewIntrospectionCommand {
        @Override
        public MonkeySourceNetwork.MonkeyCommandReturn query(AccessibilityNodeInfo node,
                List<String> args) {
            if (args.size() != 0) {
                return MonkeySourceNetwork.EARG;
            }
            return new MonkeySourceNetwork.MonkeyCommandReturn(true,
                    Boolean.toString(node.isEnabled()));
        }
    }

    public static class GetChecked implements ViewIntrospectionCommand {
        @Override
        public MonkeySourceNetwork.MonkeyCommandReturn query(AccessibilityNodeInfo node,
                List<String> args) {
            if (args.size() != 0) {
                return MonkeySourceNetwork.EARG;
            }
            return new MonkeySourceNetwork.MonkeyCommandReturn(true,
                    Boolean.toString(node.isChecked()));
        }
    }

    public static class GetClass implements ViewIntrospectionCommand {
        @Override
        public MonkeySourceNetwork.MonkeyCommandReturn query(AccessibilityNodeInfo node,
                List<String> args) {
            if (args.size() != 0) {
                return MonkeySourceNetwork.EARG;
            }
            return new MonkeySourceNetwork.MonkeyCommandReturn(true,
                    node.getClassName().toString());
        }
    }

    public static class GetText implements ViewIntrospectionCommand {
        @Override
        public MonkeySourceNetwork.MonkeyCommandReturn query(AccessibilityNodeInfo node,
                List<String> args) {
            if (args.size() != 0) {
                return MonkeySourceNetwork.EARG;
            }

            if (node.isPassword()) {
                return new MonkeySourceNetwork.MonkeyCommandReturn(false,
                        "Node contains a password");
            }
            if (node.getText() == null) {
                return new MonkeySourceNetwork.MonkeyCommandReturn(true, "");
            }
            return new MonkeySourceNetwork.MonkeyCommandReturn(true, node.getText().toString());
        }
    }

    public static class GetLocation implements ViewIntrospectionCommand {
        @Override
        public MonkeySourceNetwork.MonkeyCommandReturn query(AccessibilityNodeInfo node,
                List<String> args) {
            if (args.size() != 0) {
                return MonkeySourceNetwork.EARG;
            }

            Rect bounds = new Rect();
            node.getBoundsInScreen(bounds);
            StringBuilder ret = new StringBuilder();
            ret.append(bounds.left).append(" ").append(bounds.top);
            ret.append(" ").append(bounds.right - bounds.left).append(" ");
            ret.append(bounds.bottom - bounds.top);
            return new MonkeySourceNetwork.MonkeyCommandReturn(true, ret.toString());
        }
    }

    public static class GetRootViewCommand implements MonkeySourceNetwork.MonkeyCommand {
        @Override
        public MonkeySourceNetwork.MonkeyCommandReturn translateCommand(List<String> command,
                MonkeySourceNetwork.CommandQueue queue) {
            return new GetAccessibilityIds().query(
                    sUiTestAutomationBridge.getRootInActiveWindow(), new ArrayList<String>());
        }
    }

    public static class GetViewsWithTextCommand implements MonkeySourceNetwork.MonkeyCommand {
        @Override
        public MonkeySourceNetwork.MonkeyCommandReturn translateCommand(List<String> command,
                MonkeySourceNetwork.CommandQueue queue) {
            if (command.size() != 2) {
                return MonkeySourceNetwork.EARG;
            }

            String text = command.get(1);
            List<AccessibilityNodeInfo> nodes = AccessibilityInteractionClient.getInstance()
                    .findAccessibilityNodeInfosByText(sUiTestAutomationBridge.getConnectionId(),
                            Integer.MAX_VALUE, AccessibilityNodeInfo.ROOT_NODE_ID, text);
            GetAccessibilityIds ids = new GetAccessibilityIds();
            List<String> empty = new ArrayList<String>();
            StringBuilder ret = new StringBuilder();

            for (AccessibilityNodeInfo node : nodes) {
                MonkeySourceNetwork.MonkeyCommandReturn result = ids.query(node, empty);
                if (!result.wasSuccessful()) {
                    return result;
                }
                ret.append(result.getMessage()).append(" ");
            }
            return new MonkeySourceNetwork.MonkeyCommandReturn(true, ret.toString());
        }
    }

    public static class ListViewsCommand implements MonkeySourceNetwork.MonkeyCommand {
        @Override
        public MonkeySourceNetwork.MonkeyCommandReturn translateCommand(List<String> command,
                MonkeySourceNetwork.CommandQueue queue) {
            AccessibilityNodeInfo root = sUiTestAutomationBridge.getRootInActiveWindow();
            if (root == null) {
                return new MonkeySourceNetwork.MonkeyCommandReturn(false, NO_ACCESSIBILITY_EVENT);
            }
            String packageName = root.getPackageName().toString();
            try {
                String sourceDir = sPm.getApplicationInfo(packageName, 0,
                        UserHandle.myUserId()).sourceDir;
                Class<?> klass = getIdClass(packageName, sourceDir);
                StringBuilder sb = new StringBuilder();
                for (Field f : klass.getFields()) {
                    sb.append(f.getName()).append(" ");
                }
                return new MonkeySourceNetwork.MonkeyCommandReturn(true, sb.toString());
            } catch (RemoteException e) {
                return new MonkeySourceNetwork.MonkeyCommandReturn(false, REMOTE_ERROR);
            } catch (ClassNotFoundException e) {
                return new MonkeySourceNetwork.MonkeyCommandReturn(false, CLASS_NOT_FOUND);
            }
        }
    }

    public static class QueryViewCommand implements MonkeySourceNetwork.MonkeyCommand {
        @Override
        public MonkeySourceNetwork.MonkeyCommandReturn translateCommand(List<String> command,
                MonkeySourceNetwork.CommandQueue queue) {
            if (command.size() <= 2) {
                return MonkeySourceNetwork.EARG;
            }

            String queryType = command.get(1);
            AccessibilityNodeInfo node = null;
            String queryCommand = null;
            List<String> args = null;

            if ("viewid".equals(queryType)) {
                try {
                    node = getNodeByViewId(command.get(2));
                    queryCommand = command.get(3);
                    args = command.subList(4, command.size());
                } catch (MonkeyViewException e) {
                    return new MonkeySourceNetwork.MonkeyCommandReturn(false, e.getMessage());
                }
            } else if ("accessibilityids".equals(queryType)) {
                try {
                    node = getNodeByAccessibilityIds(command.get(2), command.get(3));
                    queryCommand = command.get(4);
                    args = command.subList(5, command.size());
                } catch (NumberFormatException e) {
                    return MonkeySourceNetwork.EARG;
                }
            } else {
                return MonkeySourceNetwork.EARG;
            }

            if (node == null) {
                return new MonkeySourceNetwork.MonkeyCommandReturn(false, NO_NODE);
            }
            ViewIntrospectionCommand getter = COMMAND_MAP.get(queryCommand);
            if (getter == null) {
                return MonkeySourceNetwork.EARG;
            }
            return getter.query(node, args);
        }
    }

    static {
        COMMAND_MAP.put("getlocation", new GetLocation());
        COMMAND_MAP.put("gettext", new GetText());
        COMMAND_MAP.put("getclass", new GetClass());
        COMMAND_MAP.put("getchecked", new GetChecked());
        COMMAND_MAP.put("getenabled", new GetEnabled());
        COMMAND_MAP.put("getselected", new GetSelected());
        COMMAND_MAP.put("setselected", new SetSelected());
        COMMAND_MAP.put("getfocused", new GetFocused());
        COMMAND_MAP.put("setfocused", new SetFocused());
        COMMAND_MAP.put("getparent", new GetParent());
        COMMAND_MAP.put("getchildren", new GetChildren());
        COMMAND_MAP.put("getaccessibilityids", new GetAccessibilityIds());
    }

    private static Class<?> getIdClass(String packageName, String sourceDir)
            throws ClassNotFoundException {
        // The cache for the class is keyed on the package name.
        Class<?> klass = sClassMap.get(packageName);
        if (klass != null) {
            return klass;
        }

        DexClassLoader classLoader = new DexClassLoader(sourceDir, "/data/local/tmp", null,
                ClassLoader.getSystemClassLoader());
        klass = classLoader.loadClass(packageName + ".R$id");
        sClassMap.put(packageName, klass);
        return klass;
    }

    private static AccessibilityNodeInfo getNodeByViewId(String viewId)
            throws MonkeyViewException {
        AccessibilityInteractionClient client = AccessibilityInteractionClient.getInstance();
        List<AccessibilityNodeInfo> nodes = client.findAccessibilityNodeInfosByViewId(
                sUiTestAutomationBridge.getConnectionId(), Integer.MAX_VALUE,
                AccessibilityNodeInfo.ROOT_NODE_ID, viewId);
        if (nodes.isEmpty()) {
            return null;
        }
        return nodes.get(0);
    }

    private static AccessibilityNodeInfo getNodeByAccessibilityIds(String windowId,
            String viewId) {
        AccessibilityInteractionClient client = AccessibilityInteractionClient.getInstance();
        return client.findAccessibilityNodeInfoByAccessibilityId(
                sUiTestAutomationBridge.getConnectionId(), Integer.parseInt(windowId),
                Integer.parseInt(viewId), false, 0, null);
    }

    /**
     * Starts the handler thread and creates the UiAutomation connection.
     */
    public static void setup() {
        sHandlerThread.setDaemon(true);
        sHandlerThread.start();
        sUiTestAutomationBridge = new UiAutomation(sHandlerThread.getLooper(),
                new UiAutomationConnection());
        sUiTestAutomationBridge.connect();
    }

    public static void teardown() {
        sHandlerThread.quit();
    }
}
