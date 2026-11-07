package ch.cslavenc.otpforwarder

import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import ch.cslavenc.otpforwarder.ui.AppTheme
import ch.cslavenc.otpforwarder.ui.MainScreen
import ch.cslavenc.otpforwarder.ui.applyPairing
import java.io.File
import java.io.IOException

class MainActivity : ComponentActivity() {
    private var resumes by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { AppTheme { MainScreen(resumes) } }
    }

    override fun onResume() {
        super.onResume()
        importDebugPairing()
        resumes++
    }

    /**
     * Emulator pairing without a camera: `adb exec-in run-as ... files/debug-pairing.json`. Inert in release builds,
     * which are not debuggable, so nothing but the app itself can write that file. No exported surface.
     */
    private fun importDebugPairing() {
        if ((applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) == 0) return
        val file = File(filesDir, DEBUG_PAIRING_FILE)
        if (!file.exists()) return
        // The file holds the key: delete it whatever happens.
        val json = try {
            file.readText()
        } catch (e: IOException) {
            ""
        } finally {
            file.delete()
        }
        Toast.makeText(this, applyPairing(this, json), Toast.LENGTH_LONG).show()
    }

    private companion object {
        const val DEBUG_PAIRING_FILE = "debug-pairing.json"
    }
}