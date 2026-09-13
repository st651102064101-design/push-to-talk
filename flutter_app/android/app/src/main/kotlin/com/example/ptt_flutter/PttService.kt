package com.example.ptt_flutter

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.UUID

class PttService : Service() {
    private val httpClient = OkHttpClient.Builder()
        .pingInterval(15, TimeUnit.SECONDS)
        .build()
    private val reconnectExecutor: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "ptt-reconnect").apply { isDaemon = true }
        }
    private var reconnectFuture: ScheduledFuture<*>? = null
    private var heartbeatFuture: ScheduledFuture<*>? = null
    private var socket: WebSocket? = null
    private var recorder: AudioRecord? = null
    private var player: AudioTrack? = null
    private var automaticGainControl: AutomaticGainControl? = null
    private var noiseSuppressor: NoiseSuppressor? = null
    private var microphoneGain = 1f
    @Volatile private var recording = false
    private var connected = false
    private var channelBusy = false
    private var activeSpeaker = ""
    private var roomMembers = arrayListOf<String>()
    private var allMembersView = false
    private var isAdmin = false
    private var activeRoom = ""
    private var activeAuthToken = ""
    private var activeEndpoint = ""
    private var connectionGeneration = 0
    private var requestedRoom = "room-1"
    private var requestedName = "Android"
    private val clientId: String by lazy {
        val preferences = getSharedPreferences("ptt_flutter", MODE_PRIVATE)
        preferences.getString("client_id", null) ?: UUID.randomUUID().toString().also {
            preferences.edit().putString("client_id", it).apply()
        }
    }
    private var shouldReconnect = false
    private var reconnectAttempt = 0
    private lateinit var audioManager: AudioManager
    private var outputIndex = -1
    private var selectedOutput: AudioDeviceInfo? = null

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(AudioManager::class.java)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            when (intent?.action) {
                ACTION_CONNECT -> connect(intent)
                ACTION_SWITCH_ROOM -> switchRoom(intent)
                ACTION_REQUEST_STATE -> {
                    status(if (connected) "เชื่อมต่ออยู่" else "ยังไม่เชื่อมต่อ", connected)
                    if (socket == null && !shouldReconnect) stopSelf()
                }
                ACTION_TEST_SPEAKER -> testSpeaker()
                ACTION_REQUEST_TALK -> socket?.send("{\"type\":\"request-talk\"}")
                ACTION_RELEASE_TALK -> {
                    stopRecording()
                    channelBusy = false
                    activeSpeaker = ""
                    socket?.send("{\"type\":\"release-talk\"}")
                    status("พร้อมพูด", connected)
                }
                ACTION_CYCLE_OUTPUT -> cycleOutput()
                ACTION_DISCONNECT -> disconnect("ตัดการเชื่อมต่อ")
            }
        } catch (error: Exception) {
            Log.e(TAG, "PTT service action failed: ${intent?.action}", error)
            shouldReconnect = false
            cancelReconnect()
            status("เริ่มการเชื่อมต่อไม่ได้: ${error.message ?: "ข้อผิดพลาดของ Android"}")
            stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    private fun connect(intent: Intent) {
        val endpoint = intent.getStringExtra(EXTRA_ENDPOINT).orEmpty()
        val room = intent.getStringExtra(EXTRA_ROOM).orEmpty()
        val name = intent.getStringExtra(EXTRA_NAME).orEmpty().ifBlank { "Android" }
        val authToken = intent.getStringExtra(EXTRA_AUTH_TOKEN).orEmpty().trim()

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            status("ต้องอนุญาตไมโครโฟนก่อนเชื่อมต่อ")
            stopSelf()
            return
        }

        createChannel()
        try {
            startForeground(
                NOTIFICATION_ID,
                NotificationCompat.Builder(this, CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                    .setContentTitle("PTT พร้อมใช้งาน")
                    .setContentText("กำลังเชื่อมต่อห้องเสียง")
                    .setOngoing(true)
                    .build(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            )
        } catch (error: Exception) {
            Log.e(TAG, "Unable to start microphone foreground service", error)
            status("เริ่มโหมดทำงานเบื้องหลังไม่ได้: ${error.message ?: "ตรวจสอบสิทธิ์ไมโครโฟน"}")
            stopSelf()
            return
        }

        if (!endpoint.startsWith("ws://") && !endpoint.startsWith("wss://")) {
            shouldReconnect = false
            disconnect("URL ต้องขึ้นต้นด้วย ws:// หรือ wss://")
            return
        }
        requestedRoom = room
        requestedName = name
        if (socket != null && authToken == activeAuthToken && endpoint == activeEndpoint) {
            switchRoom(room, name)
            return
        }

        cancelReconnect()
        shouldReconnect = true
        reconnectAttempt = 0
        activeAuthToken = authToken
        activeEndpoint = endpoint
        activeRoom = room
        selectFirstSpeaker()

        openSocket()
    }

    private fun openSocket() {
        if (!shouldReconnect || activeEndpoint.isBlank()) return
        connectionGeneration += 1
        heartbeatFuture?.cancel(false)
        heartbeatFuture = null
        socket?.cancel()
        socket = null
        connected = false
        isAdmin = false
        activeRoom = requestedRoom
        val generation = connectionGeneration

        val requestBuilder = Request.Builder().url(activeEndpoint)
        if (activeAuthToken.isNotBlank()) {
            requestBuilder.header("Authorization", "Bearer $activeAuthToken")
        }
        val request = requestBuilder.build()
        socket = httpClient.newWebSocket(request, listener(requestedRoom, requestedName, generation))
    }

    private fun switchRoom(intent: Intent) = switchRoom(
        intent.getStringExtra(EXTRA_ROOM).orEmpty(),
        intent.getStringExtra(EXTRA_NAME).orEmpty().ifBlank { "Android" },
    )

    private fun switchRoom(room: String, name: String) {
        if (room.isBlank()) return
        requestedRoom = room
        requestedName = name
        if (socket == null) return
        stopRecording()
        activeRoom = room
        channelBusy = false
        activeSpeaker = ""
        roomMembers.clear()
        allMembersView = room == "all"
        socket?.send("{\"type\":\"release-talk\"}")
        socket?.send(joinMessage(room, name))
        status("กำลังเปลี่ยนห้อง…", connected)
    }

    private fun listener(room: String, name: String, generation: Int) = object : WebSocketListener() {
        override fun onOpen(ws: WebSocket, response: Response) {
            if (generation != connectionGeneration) {
                ws.close(1000, "superseded")
                return
            }
            startHeartbeat(ws, generation)
            status("ตรวจสอบสิทธิ์กับ Server…", false)
        }

        override fun onMessage(ws: WebSocket, text: String) {
            if (generation != connectionGeneration) return
            val message = runCatching { JSONObject(text) }.getOrNull() ?: return
            when (message.optString("type")) {
                "authenticated" -> {
                    isAdmin = message.optString("role") == "admin"
                    if (room == "all" && !isAdmin) {
                        status("Token นี้ไม่มีสิทธิ์ใช้งาน All", false, EVENT_FORBIDDEN_ALL)
                        ws.close(1008, "All requires admin")
                    } else {
                        ws.send(joinMessage(room, name))
                    }
                }
                "joined" -> {
                    connected = true
                    reconnectAttempt = 0
                    activeRoom = message.optString("room", activeRoom)
                    isAdmin = message.optString("role") == "admin"
                    channelBusy = false
                    activeSpeaker = ""
                    status("พร้อมพูด", true)
                }
                "heartbeat" -> Unit
                "floor-granted" -> startRecording()
                "floor-busy" -> {
                    channelBusy = true
                    activeSpeaker = message.optString("name", "ผู้ใช้อื่น")
                    status("ช่องสัญญาณไม่ว่าง", true, EVENT_FLOOR_BUSY)
                }
                "speaker" -> {
                    if (message.optBoolean("active")) {
                        channelBusy = true
                        activeSpeaker = message.optString("name", "ผู้ใช้อื่น")
                        status("กำลังพูด: $activeSpeaker", true)
                    } else {
                        channelBusy = false
                        activeSpeaker = ""
                        status("พร้อมพูด", true)
                    }
                }
                "members" -> updateMembers(message)
                "error" -> {
                    val event = if (message.optString("code") == "forbidden_all") {
                        EVENT_FORBIDDEN_ALL
                    } else {
                        null
                    }
                    status(message.optString("message"), connected, event)
                }
            }
        }

        override fun onMessage(ws: WebSocket, bytes: ByteString) {
            if (generation == connectionGeneration) play(bytes.toByteArray())
        }

        override fun onFailure(ws: WebSocket, error: Throwable, response: Response?) {
            closeFromSocket(
                if (response?.code == 401) "Access Token ไม่ถูกต้อง" else "เชื่อมต่อไม่ได้: ${error.message}",
                generation,
                response?.code != 401,
            )
        }

        override fun onClosed(ws: WebSocket, code: Int, reason: String) {
            closeFromSocket(
                when (code) {
                    SOCKET_REPLACED_CODE -> "มีการเชื่อมต่อใหม่จากแอปนี้แล้ว"
                    SOCKET_DASHBOARD_CODE -> "ถูกตัดการเชื่อมต่อโดยผู้ดูแลระบบ"
                    else -> "ตัดการเชื่อมต่อ"
                },
                generation,
                code != SOCKET_REPLACED_CODE && code != SOCKET_DASHBOARD_CODE,
            )
        }
    }

    private fun startHeartbeat(ws: WebSocket, generation: Int) {
        heartbeatFuture?.cancel(false)
        heartbeatFuture = reconnectExecutor.scheduleAtFixedRate({
            if (generation == connectionGeneration && ws == socket && ws.send(
                    JSONObject()
                        .put("type", "heartbeat")
                        .put("ts", System.currentTimeMillis())
                        .toString(),
                ).not()) {
                ws.cancel()
            }
        }, HEARTBEAT_INTERVAL_SECONDS, HEARTBEAT_INTERVAL_SECONDS, TimeUnit.SECONDS)
    }

    private fun updateMembers(message: JSONObject) {
        allMembersView = message.optBoolean("allView")
        val source = message.optJSONArray("members")
        roomMembers = arrayListOf<String>().apply {
            if (source != null) {
                for (index in 0 until source.length()) {
                    val member = source.optJSONObject(index) ?: continue
                    val memberName = member.optString("name", "Android").ifBlank { "Android" }
                    val selfSuffix = if (member.optBoolean("self")) " (คุณ)" else ""
                    val roomSuffix = if (allMembersView) " • ${roomLabel(member.optString("room"))}" else ""
                    add(memberName + selfSuffix + roomSuffix)
                }
            }
        }
        status("อัปเดตรายชื่อออนไลน์", connected)
    }

    private fun startRecording() {
        if (recording) return
        val size = AudioRecord.getMinBufferSize(RATE, INPUT_CHANNEL, PCM).coerceAtLeast(FRAME_BYTES * 4)
        recorder = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            RATE,
            INPUT_CHANNEL,
            PCM,
            size,
        ).also {
            microphoneGain = 1f
            if (AutomaticGainControl.isAvailable()) {
                automaticGainControl = AutomaticGainControl.create(it.audioSessionId)?.apply { enabled = true }
            }
            if (NoiseSuppressor.isAvailable()) {
                noiseSuppressor = NoiseSuppressor.create(it.audioSessionId)?.apply { enabled = true }
            }
            it.startRecording()
        }
        channelBusy = false
        activeSpeaker = ""
        recording = true
        status("คุณกำลังพูด", true)
        Thread {
            val frame = ByteArray(FRAME_BYTES)
            while (recording) {
                val count = recorder?.read(frame, 0, frame.size) ?: -1
                if (count > 0) socket?.send(ByteString.of(*normalizeMicrophone(frame, count)))
            }
        }.apply { name = "ptt-recorder" }.start()
    }

    private fun stopRecording() {
        recording = false
        automaticGainControl?.release()
        automaticGainControl = null
        noiseSuppressor?.release()
        noiseSuppressor = null
        recorder?.runCatching {
            stop()
            release()
        }
        recorder = null
    }

    private fun normalizeMicrophone(input: ByteArray, count: Int): ByteArray {
        val output = input.copyOf(count)
        var peak = 0
        for (index in 0 until output.size - 1 step 2) {
            val sample = ((output[index + 1].toInt() shl 8) or (output[index].toInt() and 0xFF)).toShort().toInt()
            peak = maxOf(peak, kotlin.math.abs(sample))
        }
        val desiredGain = if (peak < 300) 1f else (18_000f / peak).coerceIn(0.8f, 4f)
        microphoneGain = microphoneGain * 0.82f + desiredGain * 0.18f
        for (index in 0 until output.size - 1 step 2) {
            val sample = ((output[index + 1].toInt() shl 8) or (output[index].toInt() and 0xFF)).toShort().toInt()
            val normalized = (sample * microphoneGain).toInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            output[index] = normalized.toByte()
            output[index + 1] = (normalized shr 8).toByte()
        }
        return output
    }

    private fun play(bytes: ByteArray) {
        ensurePlayer()
        player?.write(bytes, 0, bytes.size)
    }

    private fun ensurePlayer() {
        if (player != null) return
        val size = AudioTrack.getMinBufferSize(RATE, OUTPUT_CHANNEL, PCM)
        audioManager.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        player = AudioTrack(
            AudioManager.STREAM_MUSIC,
            RATE,
            OUTPUT_CHANNEL,
            PCM,
            size,
            AudioTrack.MODE_STREAM,
        ).also {
            selectedOutput
                ?.takeIf { device -> device.type != AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                ?.let { device -> it.preferredDevice = device }
            it.setVolume(1f)
            it.play()
        }
    }

    private fun testSpeaker() {
        selectFirstSpeaker()
        Thread {
            ensurePlayer()
            val tone = ByteArray(RATE * 2 * 2)
            for (sampleIndex in 0 until RATE * 2) {
                val sample = (
                    Math.sin(2.0 * Math.PI * 1_000.0 * sampleIndex / RATE) *
                        Short.MAX_VALUE * 0.7
                    ).toInt()
                tone[sampleIndex * 2] = sample.toByte()
                tone[sampleIndex * 2 + 1] = (sample shr 8).toByte()
            }
            player?.write(tone, 0, tone.size)
            if (socket == null) stopSelf()
        }.apply { name = "ptt-speaker-test" }.start()
    }

    private fun joinMessage(room: String, name: String): String {
        val device = listOf(Build.MANUFACTURER, Build.MODEL)
            .filter { it.isNotBlank() }
            .joinToString(" ")
        return JSONObject()
            .put("type", "join")
            .put("room", room)
            .put("name", name)
            .put("clientId", clientId)
            .put("device", device)
            .toString()
    }

    private fun availableOutputs(): List<AudioDeviceInfo> = runCatching {
        audioManager.availableCommunicationDevices.filter {
            it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER ||
                it.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE ||
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                it.type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                it.type == AudioDeviceInfo.TYPE_BLE_SPEAKER ||
                it.type == AudioDeviceInfo.TYPE_HEARING_AID
        }
    }.getOrDefault(emptyList())

    private fun selectFirstSpeaker() {
        val outputs = availableOutputs()
        val speaker = outputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
        if (speaker != null) {
            selectedOutput = speaker
            audioManager.clearCommunicationDevice()
            audioManager.mode = AudioManager.MODE_NORMAL
            audioManager.isSpeakerphoneOn = false
            player?.preferredDevice = null
            outputIndex = outputs.indexOfFirst { it.id == speaker.id }
        } else {
            audioManager.clearCommunicationDevice()
            audioManager.mode = AudioManager.MODE_NORMAL
            audioManager.isSpeakerphoneOn = false
        }
    }

    private fun cycleOutput() {
        val outputs = availableOutputs()
        if (outputs.isEmpty()) {
            status("ไม่พบอุปกรณ์เสียง", connected)
            return
        }
        outputIndex = (outputIndex + 1).mod(outputs.size)
        val device = outputs[outputIndex]
        selectedOutput = device
        player?.preferredDevice = device
        if (device.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) {
            audioManager.clearCommunicationDevice()
            audioManager.mode = AudioManager.MODE_NORMAL
            audioManager.isSpeakerphoneOn = false
            player?.preferredDevice = null
            status("เปลี่ยนเป็น ${deviceLabel(device)}", connected)
        } else {
            audioManager.isSpeakerphoneOn = false
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            if (audioManager.setCommunicationDevice(device)) {
                status("เปลี่ยนเป็น ${deviceLabel(device)}", connected)
            } else {
                status("ไม่สามารถเปลี่ยนอุปกรณ์เสียงได้", connected)
            }
        }
    }

    private fun deviceLabel(device: AudioDeviceInfo): String = when (device.type) {
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "ลำโพง"
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "หูฟังโทรศัพท์"
        else -> "Bluetooth (${device.productName})"
    }

    private fun currentOutputLabel(): String = selectedOutput?.let(::deviceLabel)
        ?: audioManager.communicationDevice?.let(::deviceLabel)
        ?: "อัตโนมัติ"

    private fun roomLabel(room: String): String = when {
        room == "all" -> "All"
        room.startsWith("room-") -> "ห้อง ${room.removePrefix("room-")}"
        else -> room
    }

    private fun closeFromSocket(message: String, generation: Int, reconnect: Boolean) {
        if (generation != connectionGeneration) return
        connectionGeneration += 1
        heartbeatFuture?.cancel(false)
        heartbeatFuture = null
        socket = null
        resetConnectionState()
        if (reconnect && shouldReconnect) {
            scheduleReconnect(message)
        } else {
            shouldReconnect = false
            status(message)
            stopSelf()
        }
    }

    private fun disconnect(message: String) {
        shouldReconnect = false
        cancelReconnect()
        connectionGeneration += 1
        val activeSocket = socket
        heartbeatFuture?.cancel(false)
        heartbeatFuture = null
        resetConnectionState()
        activeSocket?.close(1000, "user disconnected")
        status(message)
        stopSelf()
    }

    private fun resetConnectionState() {
        stopRecording()
        socket = null
        connected = false
        isAdmin = false
        activeRoom = ""
        channelBusy = false
        activeSpeaker = ""
        roomMembers.clear()
        allMembersView = false
    }

    private fun scheduleReconnect(reason: String) {
        if (!shouldReconnect || activeEndpoint.isBlank()) return
        reconnectFuture?.cancel(false)
        val delaySeconds = 1L shl reconnectAttempt.coerceIn(0, 5)
        reconnectAttempt = (reconnectAttempt + 1).coerceAtMost(6)
        status("$reason กำลังเชื่อมต่อใหม่ใน ${delaySeconds} วินาที…", false)
        reconnectFuture = reconnectExecutor.schedule({
            reconnectFuture = null
            openSocket()
        }, delaySeconds, TimeUnit.SECONDS)
    }

    private fun cancelReconnect() {
        reconnectFuture?.cancel(false)
        reconnectFuture = null
    }

    private fun status(text: String, ready: Boolean = false, event: String? = null) {
        sendBroadcast(
            Intent(ACTION_STATUS)
                .setPackage(packageName)
                .putExtra(EXTRA_STATUS, text)
                .putExtra(EXTRA_READY, ready)
                .putExtra(EXTRA_BUSY, channelBusy)
                .putExtra(EXTRA_SPEAKER, activeSpeaker)
                .putExtra(EXTRA_SELF_TALKING, recording)
                .putExtra(EXTRA_EVENT, event)
                .putExtra(EXTRA_OUTPUT, currentOutputLabel())
                .putStringArrayListExtra(EXTRA_MEMBERS, ArrayList(roomMembers))
                .putExtra(EXTRA_ALL_MEMBERS_VIEW, allMembersView)
                .putExtra(EXTRA_ADMIN, isAdmin)
                .putExtra(EXTRA_ACTIVE_ROOM, activeRoom),
        )
    }

    private fun createChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Push-to-Talk", NotificationManager.IMPORTANCE_LOW),
        )
    }

    override fun onDestroy() {
        shouldReconnect = false
        cancelReconnect()
        heartbeatFuture?.cancel(false)
        heartbeatFuture = null
        connectionGeneration += 1
        stopRecording()
        player?.release()
        player = null
        audioManager.abandonAudioFocus(null)
        audioManager.clearCommunicationDevice()
        audioManager.mode = AudioManager.MODE_NORMAL
        socket?.close(1000, "closed")
        socket = null
        httpClient.dispatcher.executorService.shutdown()
        httpClient.connectionPool.evictAll()
        reconnectExecutor.shutdownNow()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "PttService"
        const val ACTION_CONNECT = "ptt.flutter.CONNECT"
        const val ACTION_SWITCH_ROOM = "ptt.flutter.SWITCH_ROOM"
        const val ACTION_REQUEST_STATE = "ptt.flutter.REQUEST_STATE"
        const val ACTION_TEST_SPEAKER = "ptt.flutter.TEST_SPEAKER"
        const val ACTION_REQUEST_TALK = "ptt.flutter.REQUEST_TALK"
        const val ACTION_RELEASE_TALK = "ptt.flutter.RELEASE_TALK"
        const val ACTION_CYCLE_OUTPUT = "ptt.flutter.CYCLE_OUTPUT"
        const val ACTION_DISCONNECT = "ptt.flutter.DISCONNECT"
        const val ACTION_STATUS = "ptt.flutter.STATUS"

        const val EXTRA_ENDPOINT = "endpoint"
        const val EXTRA_ROOM = "room"
        const val EXTRA_NAME = "name"
        const val EXTRA_AUTH_TOKEN = "auth_token"
        const val EXTRA_STATUS = "status"
        const val EXTRA_READY = "ready"
        const val EXTRA_OUTPUT = "output"
        const val EXTRA_BUSY = "busy"
        const val EXTRA_SPEAKER = "speaker"
        const val EXTRA_SELF_TALKING = "self_talking"
        const val EXTRA_EVENT = "event"
        const val EXTRA_MEMBERS = "members"
        const val EXTRA_ALL_MEMBERS_VIEW = "all_members_view"
        const val EXTRA_ADMIN = "admin"
        const val EXTRA_ACTIVE_ROOM = "active_room"

        const val EVENT_FLOOR_BUSY = "floor_busy"
        const val EVENT_FORBIDDEN_ALL = "forbidden_all"

        private const val CHANNEL_ID = "ptt_flutter"
        private const val NOTIFICATION_ID = 7
        private const val RATE = 16_000
        private const val INPUT_CHANNEL = AudioFormat.CHANNEL_IN_MONO
        private const val OUTPUT_CHANNEL = AudioFormat.CHANNEL_OUT_MONO
        private const val PCM = AudioFormat.ENCODING_PCM_16BIT
        private const val FRAME_BYTES = 640
        private const val HEARTBEAT_INTERVAL_SECONDS = 25L
        private const val SOCKET_REPLACED_CODE = 4001
        private const val SOCKET_DASHBOARD_CODE = 4002
    }
}
