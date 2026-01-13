package com.gap.droid.util

import android.util.Log
import com.gap.droid.BuildConfig

/**
 * Centralized logging utility that respects BuildConfig.DEBUG.
 * Logs are completely disabled in release builds for privacy protection.
 *
 * Usage:
 *   DebugLog.d(TAG, "Debug message")
 *   DebugLog.e(TAG, "Error message")
 */
object DebugLog {
    fun v(tag: String, msg: String): Int {
        return if (BuildConfig.DEBUG) Log.v(tag, msg) else 0
    }

    fun v(tag: String, msg: String, tr: Throwable): Int {
        return if (BuildConfig.DEBUG) Log.v(tag, msg, tr) else 0
    }

    fun d(tag: String, msg: String): Int {
        return if (BuildConfig.DEBUG) Log.d(tag, msg) else 0
    }

    fun d(tag: String, msg: String, tr: Throwable): Int {
        return if (BuildConfig.DEBUG) Log.d(tag, msg, tr) else 0
    }

    fun i(tag: String, msg: String): Int {
        return if (BuildConfig.DEBUG) Log.i(tag, msg) else 0
    }

    fun i(tag: String, msg: String, tr: Throwable): Int {
        return if (BuildConfig.DEBUG) Log.i(tag, msg, tr) else 0
    }

    fun w(tag: String, msg: String): Int {
        return if (BuildConfig.DEBUG) Log.w(tag, msg) else 0
    }

    fun w(tag: String, msg: String, tr: Throwable): Int {
        return if (BuildConfig.DEBUG) Log.w(tag, msg, tr) else 0
    }

    fun e(tag: String, msg: String): Int {
        return if (BuildConfig.DEBUG) Log.e(tag, msg) else 0
    }

    fun e(tag: String, msg: String, tr: Throwable): Int {
        return if (BuildConfig.DEBUG) Log.e(tag, msg, tr) else 0
    }

    fun wtf(tag: String, msg: String): Int {
        return if (BuildConfig.DEBUG) Log.wtf(tag, msg) else 0
    }

    fun wtf(tag: String, msg: String, tr: Throwable): Int {
        return if (BuildConfig.DEBUG) Log.wtf(tag, msg, tr) else 0
    }
}
