package com.example.pushtotalk

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.*
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import okhttp3.*
import okio.ByteString
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class PttService : Service() {
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
    private lateinit var audioManager: AudioManager
    private var outputIndex = -1
    private var selectedOutput: AudioDeviceInfo? = null

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(AudioManager::class.java)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CONNECT -> connect(intent)
            ACTION_SWITCH_ROOM -> switchRoom(intent)
            ACTION_REQUEST_STATE -> {
                status(if (connected) "เชื่อมต่ออยู่" else "ยังไม่เชื่อมต่อ", connected)
                if (socket == null) stopSelf()
            }
            ACTION_TEST_SPEAKER -> testSpeaker()
            ACTION_REQUEST_TALK -> socket?.send("{\"type\":\"request-talk\"}")
            ACTION_RELEASE_TALK -> {
                stopRecording()
                channelBusy = false; activeSpeaker = ""
                socket?.send("{\"type\":\"release-talk\"}")
                status("พร้อมพูด", connected)
            }
            ACTION_CYCLE_OUTPUT -> cycleOutput()
        }; return START_NOT_STICKY
    }
    private fun connect(intent: Intent) {
        val endpoint = intent.getStringExtra(EXTRA_ENDPOINT).orEmpty()
        val room = intent.getStringExtra(EXTRA_ROOM).orEmpty()
        val name = intent.getStringExtra(EXTRA_NAME).orEmpty().ifBlank { "Android" }
        if (socket != null) {
            switchRoom(room, name)
            return
        }
        createChannel()
        selectFirstSpeaker()
        startForeground(7, NotificationCompat.Builder(this, CHANNEL_ID).setSmallIcon(android.R.drawable.ic_btn_speak_now).setContentTitle("PTT พร้อมใช้งาน").setContentText("เชื่อมต่อห้องเสียงอยู่").setOngoing(true).build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        if (!endpoint.startsWith("ws://") && !endpoint.startsWith("wss://")) { close("URL ต้องขึ้นต้นด้วย ws:// หรือ wss://"); return }
        socket = OkHttpClient.Builder().pingInterval(15, TimeUnit.SECONDS).build().newWebSocket(Request.Builder().url(endpoint).build(), listener(room, name))
    }
    private fun switchRoom(intent: Intent) = switchRoom(
        intent.getStringExtra(EXTRA_ROOM).orEmpty(),
        intent.getStringExtra(EXTRA_NAME).orEmpty().ifBlank { "Android" }
    )
    private fun switchRoom(room: String, name: String) {
        if (socket == null || room.isBlank()) return
        stopRecording()
        channelBusy = false; activeSpeaker = ""
        roomMembers.clear(); allMembersView = room == "all"
        socket?.send("{\"type\":\"release-talk\"}")
        socket?.send(joinMessage(room, name))
        status("กำลังเปลี่ยนห้อง…")
    }
    private fun listener(room: String, name: String) = object : WebSocketListener() {
        override fun onOpen(ws: WebSocket, response: Response) {
            ws.send(joinMessage(room, name))
        }
        override fun onMessage(ws: WebSocket, text: String) {
            val msg = JSONObject(text)
            when (msg.optString("type")) {
                "joined" -> { connected = true; channelBusy = false; activeSpeaker = ""; status("พร้อมพูด", true) }
                "floor-granted" -> startRecording()
                "floor-busy" -> {
                    channelBusy = true
                    activeSpeaker = msg.optString("name", "ผู้ใช้อื่น")
                    status("ช่องสัญญาณไม่ว่าง", true, EVENT_FLOOR_BUSY)
                }
                "speaker" -> {
                    if (msg.optBoolean("active")) {
                        channelBusy = true
                        activeSpeaker = msg.optString("name", "ผู้ใช้อื่น")
                        status("กำลังพูด: $activeSpeaker", true)
                    } else {
                        channelBusy = false; activeSpeaker = ""
                        status("พร้อมพูด", true)
                    }
                }
                "members" -> {
                    allMembersView = msg.optBoolean("allView")
                    val members = msg.optJSONArray("members")
                    roomMembers = arrayListOf<String>().apply {
                        if (members != null) for (index in 0 until members.length()) {
                            val member = members.optJSONObject(index) ?: continue
                            val memberName = member.optString("name", "Android").ifBlank { "Android" }
                            val selfSuffix = if (member.optBoolean("self")) " (คุณ)" else ""
                            val roomSuffix = if (allMembersView) " • ${roomLabel(member.optString("room"))}" else ""
                            add(memberName + selfSuffix + roomSuffix)
                        }
                    }
                    status("อัปเดตรายชื่อออนไลน์", connected)
                }
                "error" -> status(msg.optString("message"), connected)
            }
        }
        override fun onMessage(ws: WebSocket, bytes: ByteString) = play(bytes.toByteArray())
        override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) = close("เชื่อมต่อไม่ได้: ${t.message}")
        override fun onClosed(ws: WebSocket, code: Int, reason: String) = close("ตัดการเชื่อมต่อ")
    }
    private fun startRecording() {
        if (recording) return
        val size = AudioRecord.getMinBufferSize(RATE, IN, PCM).coerceAtLeast(FRAME * 4)
        recorder = AudioRecord(MediaRecorder.AudioSource.MIC, RATE, IN, PCM, size).also {
            microphoneGain = 1f
            if (AutomaticGainControl.isAvailable()) automaticGainControl = AutomaticGainControl.create(it.audioSessionId)?.apply { enabled = true }
            if (NoiseSuppressor.isAvailable()) noiseSuppressor = NoiseSuppressor.create(it.audioSessionId)?.apply { enabled = true }
            it.startRecording()
        }
        channelBusy = false; activeSpeaker = ""
        recording = true; status("คุณกำลังพูด", true)
        Thread {
            val frame = ByteArray(FRAME)
            while (recording) {
                val count = recorder?.read(frame, 0, frame.size) ?: -1
                if (count > 0) socket?.send(ByteString.of(*normalizeMicrophone(frame, count)))
            }
        }.start()
    }
    private fun stopRecording() {
        recording = false
        automaticGainControl?.release(); automaticGainControl = null
        noiseSuppressor?.release(); noiseSuppressor = null
        recorder?.runCatching { stop(); release() }; recorder = null
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
            val normalized = (sample * microphoneGain).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
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
        if (player == null) {
            val size = AudioTrack.getMinBufferSize(RATE, OUT, PCM)
            audioManager.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            player = AudioTrack(AudioManager.STREAM_MUSIC, RATE, OUT, PCM, size, AudioTrack.MODE_STREAM).also {
                selectedOutput?.takeIf { it.type != AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }?.let { outputDevice -> it.preferredDevice = outputDevice }
                it.setVolume(1f)
                it.play()
            }
        }
    }
    private fun testSpeaker() {
        selectFirstSpeaker()
        Thread {
            ensurePlayer()
            val tone = ByteArray(RATE * 2 * 2)
            for (sampleIndex in 0 until RATE * 2) {
                val sample = (Math.sin(2.0 * Math.PI * 1_000.0 * sampleIndex / RATE) * Short.MAX_VALUE * 0.7).toInt()
                tone[sampleIndex * 2] = sample.toByte()
                tone[sampleIndex * 2 + 1] = (sample shr 8).toByte()
            }
            player?.write(tone, 0, tone.size)
            if (socket == null) stopSelf()
        }.start()
    }
    private fun joinMessage(room: String, name: String): String {
        val device = listOf(Build.MANUFACTURER, Build.MODEL).filter { it.isNotBlank() }.joinToString(" ")
        return JSONObject().put("type", "join").put("room", room).put("name", name).put("device", device).toString()
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
        val speaker = availableOutputs().firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
        if (speaker != null) {
            selectedOutput = speaker
            audioManager.clearCommunicationDevice()
            audioManager.mode = AudioManager.MODE_NORMAL
            audioManager.isSpeakerphoneOn = false
            player?.preferredDevice = null
            outputIndex = availableOutputs().indexOfFirst { it.id == speaker.id }
        } else {
            audioManager.clearCommunicationDevice()
            audioManager.mode = AudioManager.MODE_NORMAL
            audioManager.isSpeakerphoneOn = false
        }
    }
    private fun cycleOutput() {
        val outputs = availableOutputs()
        if (outputs.isEmpty()) { status("ไม่พบอุปกรณ์เสียง", connected); return }
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
            if (audioManager.setCommunicationDevice(device)) status("เปลี่ยนเป็น ${deviceLabel(device)}", connected)
            else status("ไม่สามารถเปลี่ยนอุปกรณ์เสียงได้", connected)
        }
    }
    private fun deviceLabel(device: AudioDeviceInfo): String = when (device.type) {
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "ลำโพง"
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "หูฟังโทรศัพท์"
        else -> "Bluetooth (${device.productName})"
    }
    private fun currentOutputLabel(): String = selectedOutput?.let(::deviceLabel) ?: audioManager.communicationDevice?.let(::deviceLabel) ?: "อัตโนมัติ"
    private fun roomLabel(room: String): String = when {
        room == "all" -> "All"
        room.startsWith("room-") -> "ห้อง ${room.removePrefix("room-")}"
        else -> room
    }
    private fun close(message: String) { stopRecording(); socket = null; connected = false; channelBusy = false; activeSpeaker = ""; roomMembers.clear(); allMembersView = false; status(message); stopSelf() }
    private fun status(text: String, ready: Boolean = false, event: String? = null) = sendBroadcast(
        Intent(ACTION_STATUS).setPackage(packageName)
            .putExtra(EXTRA_STATUS, text)
            .putExtra(EXTRA_READY, ready)
            .putExtra(EXTRA_BUSY, channelBusy)
            .putExtra(EXTRA_SPEAKER, activeSpeaker)
            .putExtra(EXTRA_SELF_TALKING, recording)
            .putExtra(EXTRA_EVENT, event)
            .putExtra(EXTRA_OUTPUT, currentOutputLabel())
            .putStringArrayListExtra(EXTRA_MEMBERS, ArrayList(roomMembers))
            .putExtra(EXTRA_ALL_MEMBERS_VIEW, allMembersView)
    )
    private fun createChannel() { getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(CHANNEL_ID, "Push-to-Talk", NotificationManager.IMPORTANCE_LOW)) }
    override fun onDestroy() { stopRecording(); player?.release(); audioManager.abandonAudioFocus(null); audioManager.clearCommunicationDevice(); audioManager.mode = AudioManager.MODE_NORMAL; socket?.close(1000, "closed"); super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null
    companion object {
        const val ACTION_CONNECT = "ptt.CONNECT"; const val ACTION_SWITCH_ROOM = "ptt.SWITCH_ROOM"; const val ACTION_REQUEST_STATE = "ptt.REQUEST_STATE"; const val ACTION_TEST_SPEAKER = "ptt.TEST_SPEAKER"; const val ACTION_REQUEST_TALK = "ptt.REQUEST_TALK"; const val ACTION_RELEASE_TALK = "ptt.RELEASE_TALK"; const val ACTION_CYCLE_OUTPUT = "ptt.CYCLE_OUTPUT"; const val ACTION_STATUS = "ptt.STATUS"
        const val EXTRA_ENDPOINT = "endpoint"; const val EXTRA_ROOM = "room"; const val EXTRA_NAME = "name"; const val EXTRA_STATUS = "status"; const val EXTRA_READY = "ready"; const val EXTRA_OUTPUT = "output"; const val EXTRA_BUSY = "busy"; const val EXTRA_SPEAKER = "speaker"; const val EXTRA_SELF_TALKING = "self_talking"; const val EXTRA_EVENT = "event"; const val EXTRA_MEMBERS = "members"; const val EXTRA_ALL_MEMBERS_VIEW = "all_members_view"
        const val EVENT_FLOOR_BUSY = "floor_busy"
        private const val CHANNEL_ID = "ptt"; private const val RATE = 16000; private const val IN = AudioFormat.CHANNEL_IN_MONO; private const val OUT = AudioFormat.CHANNEL_OUT_MONO; private const val PCM = AudioFormat.ENCODING_PCM_16BIT; private const val FRAME = 640
    }
}
