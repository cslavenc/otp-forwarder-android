package ch.cslavenc.otpforwarder

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import ch.cslavenc.otpforwarder.ui.AppTheme
import ch.cslavenc.otpforwarder.ui.MainScreen

class MainActivity : ComponentActivity() {
    private var resumes by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { AppTheme { MainScreen(resumes) } }
    }

    override fun onResume() {
        super.onResume()
        resumes++
    }
}