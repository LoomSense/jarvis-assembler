package com.agj.android.core

import android.os.SystemClock
import android.util.Log

/**
 * The app-wide [AgqLog] on logcat tag `AGQ` (info level), `t` from [SystemClock.elapsedRealtime] at process start.
 * Callable from any thread.
 */
object Agq {
  val log: AgqLog = AgqLog({ SystemClock.elapsedRealtime() }, { Log.i(AgqLog.TAG, it) })
}
