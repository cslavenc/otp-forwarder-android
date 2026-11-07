package ch.cslavenc.otpforwarder.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.work.WorkManager
import ch.cslavenc.otpforwarder.core.Delivery
import ch.cslavenc.otpforwarder.core.parsePairing
import ch.cslavenc.otpforwarder.data.ForwardLog
import ch.cslavenc.otpforwarder.data.PairingStore
import ch.cslavenc.otpforwarder.data.SettingsStore
import ch.cslavenc.otpforwarder.data.isKeystoreFailure
import ch.cslavenc.otpforwarder.data.prefs
import ch.cslavenc.otpforwarder.relay.ForwardWorker
import com.google.android.gms.common.moduleinstall.ModuleInstall
import com.google.android.gms.common.moduleinstall.ModuleInstallRequest
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import java.security.SecureRandom
import java.text.DateFormat
import java.util.Date

/** QUEUED entries this long past their ts never reached the worker's deadline handling (e.g. no network at all). */
private const val NOT_DELIVERED_AFTER_MS = Delivery.SEND_DEADLINE_MS + 60_000L
private const val TEST_SENDER = "Test"
private const val TEST_CODE_BOUND = 1_000_000

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme(), content = content)
}

/** The whole app UI. [refresh] changes on every resume, so setup checks re-run after a trip to Settings. */
@Composable
fun MainScreen(refresh: Int) {
    val context = LocalContext.current
    var version by remember { mutableIntStateOf(0) }
    DisposableEffect(context) {
        val prefs = context.prefs()
        // SharedPreferences holds listeners weakly; this effect keeps the strong reference until disposal.
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> version++ }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    val pairing = remember(refresh, version) { readPairing(context) }

    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier.safeDrawingPadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("OTP Forwarder", style = MaterialTheme.typography.headlineSmall)
            PairingSection(pairing)
            SetupSection(refresh)
            SendersSection(version)
            ForwardsSection(pairing != null, refresh + version)
        }
    }
}

/** Validates and stores a scanned or imported pairing; returns a fixed message that never echoes [json]. */
fun applyPairing(context: Context, json: String): String {
    val pairing = parsePairing(json).getOrElse { return "Invalid pairing QR: ${it.message}" }
    try {
        PairingStore(context).save(pairing)
    } catch (e: Exception) {
        if (!isKeystoreFailure(e)) throw e
        return "Could not store the key. Try again."
    }
    // The desktop no longer listens on the old topic.
    WorkManager.getInstance(context).cancelAllWorkByTag(ForwardWorker.TAG)
    return "Paired with ${pairing.relay}"
}

private fun readPairing(context: Context): PairingStore.Current? =
    try {
        PairingStore(context).current()
    } catch (e: Exception) {
        if (!isKeystoreFailure(e)) throw e
        null
    }

@Composable
private fun PairingSection(pairing: PairingStore.Current?) {
    val context = LocalContext.current
    var message by remember { mutableStateOf("") }
    Section("Pairing") {
        Text(pairing?.let { "Paired via ${it.relay}" } ?: "Not paired. Scan the QR code in the desktop's Pairing tab.")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { scanQr(context) { message = it } }) {
                Text(if (pairing == null) "Scan QR" else "Scan again")
            }
            if (pairing != null) {
                OutlinedButton(onClick = {
                    message = try {
                        PairingStore(context).clear()
                        WorkManager.getInstance(context).cancelAllWorkByTag(ForwardWorker.TAG)
                        "Unpaired"
                    } catch (e: Exception) {
                        if (!isKeystoreFailure(e)) throw e
                        "Could not remove the key. Try again."
                    }
                }) { Text("Unpair") }
            }
        }
        if (message.isNotEmpty()) Text(message)
    }
}

/** Downloads the Play services scanner module if needed, then scans a QR code. Cancelling does nothing. */
private fun scanQr(context: Context, onMessage: (String) -> Unit) {
    val options = GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
    val scanner = GmsBarcodeScanning.getClient(context, options)
    val unavailable = { _: Exception ->
        onMessage("Scanner unavailable (needs Google Play services). If it is downloading, try again in a minute.")
    }
    ModuleInstall.getClient(context)
        .installModules(ModuleInstallRequest.newBuilder().addApi(scanner).build())
        .addOnSuccessListener {
            scanner.startScan()
                .addOnSuccessListener { onMessage(applyPairing(context, it.rawValue.orEmpty())) }
                .addOnFailureListener(unavailable)
        }
        .addOnFailureListener(unavailable)
}

@Composable
private fun SetupSection(refresh: Int) {
    val context = LocalContext.current
    var permissionResult by remember { mutableIntStateOf(0) }
    val requestSms =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { permissionResult++ }
    val smsGranted = remember(refresh, permissionResult) {
        context.checkSelfPermission(Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED
    }
    val batteryExempt = remember(refresh) {
        context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)
    }
    val hibernationOff = remember(refresh) {
        Build.VERSION.SDK_INT < Build.VERSION_CODES.R || context.packageManager.isAutoRevokeWhitelisted
    }
    Section("Setup") {
        Check("SMS permission", smsGranted, "Grant") { requestSms.launch(Manifest.permission.RECEIVE_SMS) }
        if (!smsGranted) {
            Text(
                "If Android says \"Restricted setting\": App info > menu > Allow restricted settings, " +
                    "then grant again.",
                style = MaterialTheme.typography.bodySmall,
            )
            TextButton(onClick = { openAppInfo(context) }) { Text("Open app info") }
        }
        Check("Battery optimization off", batteryExempt, "Allow") { requestBatteryExemption(context) }
        Check("\"Pause app activity if unused\" off", hibernationOff, "App info") { openAppInfo(context) }
    }
}

@Composable
private fun Check(label: String, ok: Boolean, action: String, onAction: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("${if (ok) "OK" else "Missing"}: $label", Modifier.weight(1f))
        if (!ok) Button(onClick = onAction) { Text(action) }
    }
}

// Sideloaded personal app: delayed OTPs defeat its purpose, so ask for the exemption directly.
@SuppressLint("BatteryLife")
private fun requestBatteryExemption(context: Context) {
    try {
        context.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, packageUri(context)))
    } catch (e: ActivityNotFoundException) {
        context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
    }
}

private fun openAppInfo(context: Context) {
    context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri(context)))
}

private fun packageUri(context: Context) = Uri.fromParts("package", context.packageName, null)

@Composable
private fun SendersSection(version: Int) {
    val context = LocalContext.current
    val store = remember(context) { SettingsStore(context) }
    val senders = remember(version) { store.senders().sorted() }
    var input by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    Section("Allowed senders") {
        Text("Enter the sender exactly as your Messages app shows it. Only these senders are forwarded.")
        OutlinedTextField(
            value = input,
            onValueChange = { input = it; error = "" },
            label = { Text("Sender, e.g. +41 79 123 45 67 or PostFinance") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Button(onClick = {
            if (store.addSender(input)) input = "" else error = "Empty, too long or already in the list"
        }) { Text("Add") }
        if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error)
        if (senders.isEmpty()) Text("No senders yet: nothing is forwarded.")
        senders.forEach { sender ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(sender, Modifier.weight(1f))
                TextButton(onClick = { store.removeSender(sender) }) { Text("Remove") }
            }
        }
    }
}

@Composable
private fun ForwardsSection(paired: Boolean, refresh: Int) {
    val context = LocalContext.current
    var testCode by remember { mutableStateOf("") }
    val entries = remember(refresh) { ForwardLog.entries(context) }
    val now = remember(refresh) { System.currentTimeMillis() }
    val format = remember { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM) }
    Section("Recent forwards") {
        Button(enabled = paired, onClick = {
            val code = SecureRandom().nextInt(TEST_CODE_BOUND).toString().padStart(6, '0')
            ForwardWorker.forward(context, TEST_SENDER, code, System.currentTimeMillis())
            testCode = code
        }) { Text("Send test code") }
        if (testCode.isNotEmpty()) Text("Test code $testCode sent. Compare it with what the desktop types.")
        if (entries.isEmpty()) Text("Nothing forwarded yet.")
        entries.forEach { e ->
            val detail = if (e.detail.isEmpty()) "" else " (${e.detail})"
            Text("${format.format(Date(e.ts))}  ${e.sender.ifEmpty { "?" }}  ${label(e, now)}$detail")
        }
    }
}

private fun label(e: ForwardLog.Entry, now: Long) = when (e.status) {
    ForwardLog.Status.QUEUED -> if (now > e.ts + NOT_DELIVERED_AFTER_MS) "Not delivered" else "Queued"
    ForwardLog.Status.SENT -> "Sent"
    ForwardLog.Status.FAILED -> "Failed"
    ForwardLog.Status.NO_CODE -> "No code found"
    ForwardLog.Status.TOO_OLD -> "Too old"
    ForwardLog.Status.NOT_PAIRED -> "Not paired"
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}
