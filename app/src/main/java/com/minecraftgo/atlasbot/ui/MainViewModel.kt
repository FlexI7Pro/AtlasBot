package com.minecraftgo.atlasbot.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.minecraftgo.atlasbot.data.BotConfig
import com.minecraftgo.atlasbot.data.ExecutionState
import com.minecraftgo.atlasbot.data.LogEntry
import rikka.shizuku.Shizuku
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.time.LocalTime
import java.time.format.DateTimeFormatter

class MainViewModel : ViewModel() {

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        _shizukuActive.value = true
        _shizukuPermission.value = Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        _shizukuActive.value = false
        _shizukuPermission.value = false
    }

    private val requestPermissionResultListener = Shizuku.OnRequestPermissionResultListener { _, grantResult ->
        _shizukuPermission.value = grantResult == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    init {
        Shizuku.addBinderReceivedListener(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)
        Shizuku.addRequestPermissionResultListener(requestPermissionResultListener)
    }

    override fun onCleared() {
        super.onCleared()
        Shizuku.removeBinderReceivedListener(binderReceivedListener)
        Shizuku.removeBinderDeadListener(binderDeadListener)
        Shizuku.removeRequestPermissionResultListener(requestPermissionResultListener)
    }

    private val _logs = MutableStateFlow<List<LogEntry>>(emptyList())
    val logs = _logs.asStateFlow()

    private val _executionState = MutableStateFlow(ExecutionState.IDLE)
    val executionState = _executionState.asStateFlow()

    private val _isBinaryReady = MutableStateFlow(false)
    val isBinaryReady = _isBinaryReady.asStateFlow()

    private val _shizukuActive = MutableStateFlow(false)
    val shizukuActive = _shizukuActive.asStateFlow()

    private val _shizukuPermission = MutableStateFlow(false)
    val shizukuPermission = _shizukuPermission.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading = _isLoading.asStateFlow()

    private var process: Process? = null
    private var logJob: Job? = null

    private val BINARY_NAME = "atlasbot-android"
    private val TMP_PATH = "/data/local/tmp/$BINARY_NAME"

    fun checkStatus(context: Context) {
        viewModelScope.launch {
            val isActive = Shizuku.pingBinder()
            _shizukuActive.value = isActive
            
            if (isActive) {
                val hasPermission = Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED
                _shizukuPermission.value = hasPermission
                if (!hasPermission) {
                    addLog("Shizuku is running, but permission is missing.")
                }
            } else {
                _shizukuPermission.value = false
            }
            
            val localFile = File(context.filesDir, BINARY_NAME)
            _isBinaryReady.value = localFile.exists()
        }
    }

    fun requestShizukuPermission() {
        if (Shizuku.pingBinder()) {
            Shizuku.requestPermission(0)
        }
    }

    fun handleShizukuClick(context: Context) {
        addLog("Checking Shizuku status...")
        val isActive = Shizuku.pingBinder()
        _shizukuActive.value = isActive
        
        if (!isActive) {
            addLog("Shizuku service not detected. Opening app...")
            // Try to open Shizuku app if installed
            val intent = context.packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
            if (intent != null) {
                context.startActivity(intent)
            } else {
                openShizukuPlayStore(context)
            }
        } else {
            val hasPermission = Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED
            _shizukuPermission.value = hasPermission
            if (!hasPermission) {
                addLog("Requesting Shizuku permission...")
                requestShizukuPermission()
            } else {
                addLog("Shizuku is already connected and authorized.")
            }
        }
    }

    fun openShizukuPlayStore(context: Context) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=moe.shizuku.privileged.api"))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (e: Exception) {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=moe.shizuku.privileged.api"))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }
    }

    fun importBinary(context: Context, uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            _isLoading.value = true
            try {
                val localFile = File(context.filesDir, BINARY_NAME)
                context.contentResolver.openInputStream(uri)?.use { input ->
                    localFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                _isBinaryReady.value = true
                addLog("Binary imported successfully: $BINARY_NAME")
            } catch (e: Exception) {
                addLog("Failed to import binary: ${e.message}", true)
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun startBot(context: Context, config: BotConfig) {
        if (_executionState.value == ExecutionState.RUNNING) return
        if (!shizukuPermission.value) {
            addLog("Error: Shizuku permission not granted.", true)
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            _isLoading.value = true
            _executionState.value = ExecutionState.STARTING
            
            val localFile = File(context.filesDir, BINARY_NAME)
            if (!localFile.exists()) {
                addLog("Error: Binary not found in app storage.", true)
                _executionState.value = ExecutionState.IDLE
                _isLoading.value = false
                return@launch
            }

            try {
                addLog("Moving binary to /data/local/tmp/...")
                // Copy to /data/local/tmp using Shizuku
                val copyCmd = arrayOf("sh", "-c", "cat > $TMP_PATH")
                val copyProcess = Shizuku.newProcess(copyCmd, null, null)
                localFile.inputStream().use { input ->
                    copyProcess.outputStream.use { output ->
                        input.copyTo(output)
                    }
                }
                copyProcess.waitFor()
                
                addLog("Setting permissions...")
                Shizuku.newProcess(arrayOf("chmod", "755", TMP_PATH), null, null).waitFor()

                // Execute: ./atlasbot-android connect --offline="name" ip:port
                val execCmd = arrayOf(
                    TMP_PATH,
                    "connect",
                    "--offline=\"${config.name}\"",
                    "${config.ip}:${config.port}"
                )

                addLog("Executing: ${execCmd.joinToString(" ")}")
                process = Shizuku.newProcess(execCmd, null, null)
                _executionState.value = ExecutionState.RUNNING
                _isLoading.value = false

                logJob = launch {
                    process?.inputStream?.bufferedReader()?.useLines { lines ->
                        lines.forEach { line -> addLog(line) }
                    }
                    process?.errorStream?.bufferedReader()?.useLines { lines ->
                        lines.forEach { line -> addLog(log = line, isError = true) }
                    }
                    _executionState.value = ExecutionState.IDLE
                    addLog("Process exited.")
                }

            } catch (e: Exception) {
                addLog("Execution failed: ${e.message}", true)
                _executionState.value = ExecutionState.ERROR
                _isLoading.value = false
            }
        }
    }

    fun stopBot() {
        process?.destroy()
        logJob?.cancel()
        _executionState.value = ExecutionState.STOPPING
        addLog("Stopping bot...")
    }

    private fun addLog(log: String, isError: Boolean = false) {
        val timestamp = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"))
        val newEntry = LogEntry(timestamp, log, isError)
        _logs.value = _logs.value + newEntry
    }

    fun clearLogs() {
        _logs.value = emptyList()
    }
}
