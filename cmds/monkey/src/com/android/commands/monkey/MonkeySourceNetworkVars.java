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

import android.hardware.display.DisplayManagerGlobal;
import android.os.Build;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.view.Display;

import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Provides the MonkeySourceNetwork with the set of variables it supports.
 */
public class MonkeySourceNetworkVars {

    /**
     * Interface to get one of the values supported by the monkey.
     */
    private interface VarGetter {
        String get();
    }

    private static class StaticVarGetter implements VarGetter {
        private final String value;

        public StaticVarGetter(String value) {
            this.value = value;
        }

        @Override
        public String get() {
            return value;
        }
    }

    // Use a TreeMap to make the output sorted.
    private static final Map<String, VarGetter> VAR_MAP = new TreeMap<String, VarGetter>();

    static {
        VAR_MAP.put("build.board", new StaticVarGetter(Build.BOARD));
        VAR_MAP.put("build.brand", new StaticVarGetter(Build.BRAND));
        VAR_MAP.put("build.device", new StaticVarGetter(Build.DEVICE));
        VAR_MAP.put("build.display", new StaticVarGetter(Build.DISPLAY));
        VAR_MAP.put("build.fingerprint", new StaticVarGetter(Build.FINGERPRINT));
        VAR_MAP.put("build.host", new StaticVarGetter(Build.HOST));
        VAR_MAP.put("build.id", new StaticVarGetter(Build.ID));
        VAR_MAP.put("build.model", new StaticVarGetter(Build.MODEL));
        VAR_MAP.put("build.product", new StaticVarGetter(Build.PRODUCT));
        VAR_MAP.put("build.tags", new StaticVarGetter(Build.TAGS));
        VAR_MAP.put("build.brand", new StaticVarGetter(Long.toString(Build.TIME)));
        VAR_MAP.put("build.type", new StaticVarGetter(Build.TYPE));
        VAR_MAP.put("build.user", new StaticVarGetter(Build.USER));
        VAR_MAP.put("build.cpu_abi", new StaticVarGetter(Build.CPU_ABI));
        VAR_MAP.put("build.manufacturer", new StaticVarGetter(Build.MANUFACTURER));
        VAR_MAP.put("build.version.incremental", new StaticVarGetter(Build.VERSION.INCREMENTAL));
        VAR_MAP.put("build.version.release", new StaticVarGetter(Build.VERSION.RELEASE));
        VAR_MAP.put("build.version.sdk", new StaticVarGetter(Integer.toString(Build.VERSION.SDK_INT)));
        VAR_MAP.put("build.version.codename", new StaticVarGetter(Build.VERSION.CODENAME));

        // display
        Display display = DisplayManagerGlobal.getInstance().getRealDisplay(Display.DEFAULT_DISPLAY);
        VAR_MAP.put("display.width", new StaticVarGetter(Integer.toString(display.getWidth())));
        VAR_MAP.put("display.height", new StaticVarGetter(Integer.toString(display.getHeight())));
        DisplayMetrics dm = new DisplayMetrics();
        display.getMetrics(dm);
        VAR_MAP.put("display.density", new StaticVarGetter(Float.toString(dm.density)));

        // am
        VAR_MAP.put("am.current.package", new VarGetter() {
            @Override
            public String get() {
                return Monkey.currentPackage;
            }
        });
        VAR_MAP.put("am.current.action", new VarGetter() {
            @Override
            public String get() {
                if (Monkey.currentIntent == null) {
                    return null;
                }
                return Monkey.currentIntent.getAction();
            }
        });
        VAR_MAP.put("am.current.comp.class", new VarGetter() {
            @Override
            public String get() {
                if (Monkey.currentIntent == null) {
                    return null;
                }
                return Monkey.currentIntent.getComponent().getClassName();
            }
        });
        VAR_MAP.put("am.current.comp.package", new VarGetter() {
            @Override
            public String get() {
                if (Monkey.currentIntent == null) {
                    return null;
                }
                return Monkey.currentIntent.getComponent().getPackageName();
            }
        });
        VAR_MAP.put("am.current.data", new VarGetter() {
            @Override
            public String get() {
                if (Monkey.currentIntent == null) {
                    return null;
                }
                return Monkey.currentIntent.getDataString();
            }
        });
        VAR_MAP.put("am.current.categories", new VarGetter() {
            @Override
            public String get() {
                if (Monkey.currentIntent == null) {
                    return null;
                }
                StringBuffer buffer = new StringBuffer();
                Iterator<String> it = Monkey.currentIntent.getCategories().iterator();
                while (it.hasNext()) {
                    buffer.append(it.next()).append(" ");
                }
                return buffer.toString();
            }
        });

        // clock
        VAR_MAP.put("clock.realtime", new VarGetter() {
            @Override
            public String get() {
                return Long.toString(SystemClock.elapsedRealtime());
            }
        });
        VAR_MAP.put("clock.uptime", new VarGetter() {
            @Override
            public String get() {
                return Long.toString(SystemClock.uptimeMillis());
            }
        });
        VAR_MAP.put("clock.millis", new VarGetter() {
            @Override
            public String get() {
                return Long.toString(System.currentTimeMillis());
            }
        });
        VAR_MAP.put("monkey.version", new VarGetter() {
            @Override
            public String get() {
                return Integer.toString(MonkeySourceNetwork.MONKEY_NETWORK_VERSION);
            }
        });
    }

    public static class GetVarCommand implements MonkeySourceNetwork.MonkeyCommand {
        @Override
        public MonkeySourceNetwork.MonkeyCommandReturn translateCommand(List<String> command,
                MonkeySourceNetwork.CommandQueue queue) {
            if (command.size() != 2) {
                return MonkeySourceNetwork.EARG;
            }
            VarGetter getter = VAR_MAP.get(command.get(1));
            if (getter == null) {
                return new MonkeySourceNetwork.MonkeyCommandReturn(false, "unknown var");
            }
            return new MonkeySourceNetwork.MonkeyCommandReturn(true, getter.get());
        }
    }

    public static class ListVarCommand implements MonkeySourceNetwork.MonkeyCommand {
        @Override
        public MonkeySourceNetwork.MonkeyCommandReturn translateCommand(List<String> command,
                MonkeySourceNetwork.CommandQueue queue) {
            Set<String> keys = VAR_MAP.keySet();
            StringBuffer sb = new StringBuffer();
            for (String key : keys) {
                sb.append(key).append(" ");
            }
            return new MonkeySourceNetwork.MonkeyCommandReturn(true, sb.toString());
        }
    }
}
