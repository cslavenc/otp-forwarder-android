package ch.cslavenc.otpforwarder.data

import android.content.Context
import android.content.SharedPreferences

/** The app's single preferences file, shared by all stores (one process). */
internal fun Context.prefs(): SharedPreferences = getSharedPreferences("otpfwd", Context.MODE_PRIVATE)
