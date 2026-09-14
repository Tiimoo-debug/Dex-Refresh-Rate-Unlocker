package com.tiimoo.dexrefresh.probe;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * References captured by the hooks, consumed by the snapshot code.
 *
 * <p>Holding onto server singletons like this is safe (they live for the life
 * of system_server) and saves the snapshot thread from having to find them
 * again through a binder call.
 */
public final class ProbeState {

    private ProbeState() {
    }

    public static volatile ClassLoader systemServerClassLoader;

    /** com.android.server.display.DisplayManagerService */
    public static volatile Object displayManagerService;

    /** com.android.server.display.mode.DisplayModeDirector (package varies) */
    public static volatile Object displayModeDirector;

    /** com.android.server.display.LogicalDisplayMapper */
    public static volatile Object logicalDisplayMapper;

    /** system_server Context, resolved at boot-completed. */
    public static volatile Object systemContext;

    /** com.android.server.display.mode.VotesStorage, and its vote entry points. */
    public static volatile Object votesStorage;
    public static volatile java.lang.reflect.Method updateVoteMethod;
    public static volatile java.lang.reflect.Method updateGlobalVoteMethod;

    /** priority int -> constant name, harvested from the Vote class. */
    public static final Map<Integer, String> VOTE_PRIORITY_NAMES = new ConcurrentHashMap<>();

    /** Last value seen at each observed choke point, for snapshot replay. */
    public static final Map<String, String> LAST_SEEN = new ConcurrentHashMap<>();

    public static void record(String key, String value) {
        if (key != null && value != null) {
            LAST_SEEN.put(key, value);
        }
    }

    public static String votePriorityName(int priority) {
        String name = VOTE_PRIORITY_NAMES.get(priority);
        return name == null ? "PRIORITY_?" : name;
    }
}
