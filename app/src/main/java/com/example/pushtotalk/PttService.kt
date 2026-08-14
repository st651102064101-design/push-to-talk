package com.example.pushtotalk

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.*
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
    @Volatile private var recording = false
    private var connected = false
    private lateinit var audioManager: AudioManager
    private var outputIndex = -1

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(AudioManager::class.java)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CONNECT -> connect(intent)
            ACTION_SWITCH_ROOM -> switchRoom(intent)
            ACTION_REQUEST_TALK -> socket?.send("{\"type\":\"request-talk\"}")
            ACTION_RELEASE_TALK -> { stopRecording(); socket?.send("{\"type\":\"release-talk\"}") }
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
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
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
                "joined" -> { connected = true; status("อยู่ในห้อง ${msg.optString("room")} — พร้อมพูด", true) }
                "floor-granted" -> startRecording()
                "floor-busy" -> status("มีคนกำลังพูดอยู่", true)
                "speaker" -> if (!msg.optBoolean("active")) status("พร้อมพูด", true)
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
        recorder = AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, RATE, IN, PCM, size).also { it.startRecording() }
        recording = true; status("กำลังพูด", true)
        Thread {
            val frame = ByteArray(FRAME)
            while (recording) { val count = recorder?.read(frame, 0, frame.size) ?: -1; if (count > 0) socket?.send(ByteString.of(*frame.copyOf(count))) }
        }.start()
    }
    private fun stopRecording() { recording = false; recorder?.runCatching { stop(); release() }; recorder = null }
    private fun play(bytes: ByteArray) {
        if (player == null) {
            val size = AudioTrack.getMinBufferSize(RATE, OUT, PCM)
            player = AudioTrack.Builder().setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION).build()).setAudioFormat(AudioFormat.Builder().setSampleRate(RATE).setEncoding(PCM).setChannelMask(OUT).build()).setBufferSizeInBytes(size).setTransferMode(AudioTrack.MODE_STREAM).build().also { it.play() }
        }; player?.write(bytes, 0, bytes.size)
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
        if (speaker != null && audioManager.setCommunicationDevice(speaker)) outputIndex = availableOutputs().indexOfFirst { it.id == speaker.id }
    }
    private fun cycleOutput() {
        val outputs = availableOutputs()
        if (outputs.isEmpty()) { status("ไม่พบอุปกรณ์เสียง", connected); return }
        outputIndex = (outputIndex + 1).mod(outputs.size)
        val device = outputs[outputIndex]
        if (audioManager.setCommunicationDevice(device)) status("เปลี่ยนเป็น ${deviceLabel(device)}", connected)
        else status("ไม่สามารถเปลี่ยนอุปกรณ์เสียงได้", connected)
    }
    private fun deviceLabel(device: AudioDeviceInfo): String = when (device.type) {
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "ลำโพง"
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "หูฟังโทรศัพท์"
        else -> "Bluetooth (${device.productName})"
    }
    private fun currentOutputLabel(): String = audioManager.communicationDevice?.let(::deviceLabel) ?: "อัตโนมัติ"
    private fun close(message: String) { stopRecording(); socket = null; connected = false; status(message); stopSelf() }
    private fun status(text: String, ready: Boolean = false) = sendBroadcast(Intent(ACTION_STATUS).setPackage(packageName).putExtra(EXTRA_STATUS, text).putExtra(EXTRA_READY, ready).putExtra(EXTRA_OUTPUT, currentOutputLabel()))
    private fun createChannel() { getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(CHANNEL_ID, "Push-to-Talk", NotificationManager.IMPORTANCE_LOW)) }
    override fun onDestroy() { stopRecording(); player?.release(); audioManager.clearCommunicationDevice(); audioManager.mode = AudioManager.MODE_NORMAL; socket?.close(1000, "closed"); super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null
    companion object {
        const val ACTION_CONNECT = "ptt.CONNECT"; const val ACTION_SWITCH_ROOM = "ptt.SWITCH_ROOM"; const val ACTION_REQUEST_TALK = "ptt.REQUEST_TALK"; const val ACTION_RELEASE_TALK = "ptt.RELEASE_TALK"; const val ACTION_CYCLE_OUTPUT = "ptt.CYCLE_OUTPUT"; const val ACTION_STATUS = "ptt.STATUS"
        const val EXTRA_ENDPOINT = "endpoint"; const val EXTRA_ROOM = "room"; const val EXTRA_NAME = "name"; const val EXTRA_STATUS = "status"; const val EXTRA_READY = "ready"; const val EXTRA_OUTPUT = "output"
        private const val CHANNEL_ID = "ptt"; private const val RATE = 16000; private const val IN = AudioFormat.CHANNEL_IN_MONO; private const val OUT = AudioFormat.CHANNEL_OUT_MONO; private const val PCM = AudioFormat.ENCODING_PCM_16BIT; private const val FRAME = 640
    }
}
