package com.janeasystems.nodejsmobile;

import android.util.Log;

/**
 * NodeJsMobile - thin Java wrapper around the Node.js Mobile native bridge.
 *
 * Usage:
 *   NodeJsMobile.startNodeWithArguments(new String[]{"node", "/path/to/main.js"});
 */
public class NodeJsMobile {

    private static final String TAG = "NodeJsMobile";

    static {
        System.loadLibrary("node");
        System.loadLibrary("nodejs-mobile-native");
    }

    /** Returns the ABI name for the current device (e.g. "arm64-v8a"). */
    public native String getCurrentABIName();

    /**
     * Starts Node.js with the given argument array.
     * The first argument is typically "node", followed by a script path or "-e" script.
     *
     * This call BLOCKS until Node exits — run it on a background thread.
     *
     * @param arguments  argv for Node; e.g. {"node", "/path/to/main.js"}
     * @param redirectOutputToLogcat  if true, stdout/stderr are mirrored to logcat
     * @return Node exit code
     */
    public native int startNodeWithArguments(String[] arguments, boolean redirectOutputToLogcat);

    /**
     * Convenience overload that always redirects output to logcat.
     */
    public int startNodeWithArguments(String[] arguments) {
        return startNodeWithArguments(arguments, true);
    }
}
