package com.example.ptt_flutter

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodChannel

class MainActivity : FlutterActivity(), EventChannel.StreamHandler {
    private var eventSink: EventChannel.EventSink? = null
    private var receiverRegistered = false
    private var pendingPermissionResult: MethodChannel.Result? = null

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != PttService.ACTION_STATUS) return
            eventSink?.success(
                mapOf(
                    "status" to intent.getStringExtra(PttService.EXTRA_STATUS).orEmpty(),
                    "ready" to intent.getBooleanExtra(PttService.EXTRA_READY, false),
                    "output" to intent.getStringExtra(PttService.EXTRA_OUTPUT).orEmpty(),
                    "busy" to intent.getBooleanExtra(PttService.EXTRA_BUSY, false),
                    "speaker" to intent.getStringExtra(PttService.EXTRA_SPEAKER).orEmpty(),
                    "selfTalking" to intent.getBooleanExtra(PttService.EXTRA_SELF_TALKING, false),
                    "event" to intent.getStringExtra(PttService.EXTRA_EVENT),
                    "members" to (intent.getStringArrayListExtra(PttService.EXTRA_MEMBERS) ?: arrayListOf<String>()),
                    "allMembersView" to intent.getBooleanExtra(PttService.EXTRA_ALL_MEMBERS_VIEW, false),
                    "admin" to intent.getBooleanExtra(PttService.EXTRA_ADMIN, false),
                    "activeRoom" to intent.getStringExtra(PttService.EXTRA_ACTIVE_ROOM).orEmpty(),
                ),
            )
        }
    }

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CONTROL_CHANNEL)
            .setMethodCallHandler { call, result ->
                try {
                    when (call.method) {
                        "requestPermissions" -> requestPttPermissions(result)
                        "appVersion" -> {
                            val packageInfo = packageManager.getPackageInfo(
                                packageName,
                                PackageManager.PackageInfoFlags.of(0),
                            )
                            result.success("${packageInfo.versionName} (${packageInfo.longVersionCode})")
                        }
                        "connect" -> {
                            val intent = serviceIntent(PttService.ACTION_CONNECT)
                                .putExtra(PttService.EXTRA_ENDPOINT, call.argument<String>("endpoint").orEmpty())
                                .putExtra(PttService.EXTRA_ROOM, call.argument<String>("room").orEmpty())
                                .putExtra(PttService.EXTRA_NAME, call.argument<String>("name").orEmpty())
                                .putExtra(PttService.EXTRA_AUTH_TOKEN, call.argument<String>("token").orEmpty())
                            ContextCompat.startForegroundService(this, intent)
                            result.success(null)
                        }
                        "switchRoom" -> {
                            startService(
                                serviceIntent(PttService.ACTION_SWITCH_ROOM)
                                    .putExtra(PttService.EXTRA_ROOM, call.argument<String>("room").orEmpty())
                                    .putExtra(PttService.EXTRA_NAME, call.argument<String>("name").orEmpty()),
                            )
                            result.success(null)
                        }
                        "requestTalk" -> startAction(PttService.ACTION_REQUEST_TALK, result)
                        "releaseTalk" -> startAction(PttService.ACTION_RELEASE_TALK, result)
                        "disconnect" -> startAction(PttService.ACTION_DISCONNECT, result)
                        "requestState" -> startAction(PttService.ACTION_REQUEST_STATE, result)
                        "cycleOutput" -> startAction(PttService.ACTION_CYCLE_OUTPUT, result)
                        "testSpeaker" -> startAction(PttService.ACTION_TEST_SPEAKER, result)
                        else -> result.notImplemented()
                    }
                } catch (error: Exception) {
                    result.error("PTT_NATIVE_ERROR", error.message ?: "Android PTT service failed", null)
                }
            }
        EventChannel(flutterEngine.dartExecutor.binaryMessenger, STATUS_CHANNEL)
            .setStreamHandler(this)
    }

    override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
        eventSink = events
        if (!receiverRegistered) {
            ContextCompat.registerReceiver(
                this,
                statusReceiver,
                IntentFilter(PttService.ACTION_STATUS),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            receiverRegistered = true
        }
        startService(serviceIntent(PttService.ACTION_REQUEST_STATE))
    }

    override fun onCancel(arguments: Any?) {
        eventSink = null
        unregisterStatusReceiver()
    }

    override fun cleanUpFlutterEngine(flutterEngine: FlutterEngine) {
        unregisterStatusReceiver()
        super.cleanUpFlutterEngine(flutterEngine)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != PTT_PERMISSION_REQUEST) return
        val microphoneGranted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED
        pendingPermissionResult?.success(microphoneGranted)
        pendingPermissionResult = null
    }

    private fun requestPttPermissions(result: MethodChannel.Result) {
        val requiredPermissions = listOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.POST_NOTIFICATIONS,
            Manifest.permission.BLUETOOTH_CONNECT,
        )
        val missingPermissions = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missingPermissions.isEmpty()) {
            result.success(true)
            return
        }
        if (pendingPermissionResult != null) {
            result.error("PERMISSION_PENDING", "Permission request is already open", null)
            return
        }
        pendingPermissionResult = result
        ActivityCompat.requestPermissions(
            this,
            missingPermissions.toTypedArray(),
            PTT_PERMISSION_REQUEST,
        )
    }

    private fun startAction(action: String, result: MethodChannel.Result) {
        startService(serviceIntent(action))
        result.success(null)
    }

    private fun serviceIntent(action: String) = Intent(this, PttService::class.java).setAction(action)

    private fun unregisterStatusReceiver() {
        if (!receiverRegistered) return
        runCatching { unregisterReceiver(statusReceiver) }
        receiverRegistered = false
    }

    companion object {
        private const val CONTROL_CHANNEL = "ptt/control"
        private const val STATUS_CHANNEL = "ptt/status"
        private const val PTT_PERMISSION_REQUEST = 701
    }
}
