import AVFoundation
import AudioToolbox
import Flutter
import Foundation
import PushToTalk
import Security
import UIKit

@available(iOS 16.0, *)
final class PttBridge: NSObject, FlutterStreamHandler {
  private static let channelUUID = UUID(uuidString: "5AA00000-7B3D-4FD3-A705-1290A0E0F771")!
  private let controlChannel: FlutterMethodChannel
  private let statusChannel: FlutterEventChannel
  private var eventSink: FlutterEventSink?
  private var channelManager: PTChannelManager?
  private var webSocketSession: URLSession?
  private var webSocket: URLSessionWebSocketTask?
  private var pingTimer: Timer?
  private var endpoint = ""
  private var room = "room-1"
  private var displayName = "iPhone"
  private var authToken = ""
  private var ephemeralPushToken: Data?
  private var members: [String] = []
  private var activeSpeaker = ""
  private var outputLabel = "อัตโนมัติ"
  private var connected = false
  private var busy = false
  private var selfTalking = false
  private var allMembersView = false
  private var isAdmin = false
  private var floorGranted = false
  private var systemTransmitting = false
  private var audioSessionActive = false
  private var recordEngine: AVAudioEngine?
  private var playbackEngine: AVAudioEngine?
  private var playbackNode: AVAudioPlayerNode?
  private var pendingAudio: [Data] = []
  private var shouldReconnect = false

  init(messenger: FlutterBinaryMessenger) {
    controlChannel = FlutterMethodChannel(name: "ptt/control", binaryMessenger: messenger)
    statusChannel = FlutterEventChannel(name: "ptt/status", binaryMessenger: messenger)
    super.init()
    controlChannel.setMethodCallHandler { [weak self] call, result in
      self?.handle(call, result: result)
    }
    statusChannel.setStreamHandler(self)
    loadConfiguration()
    createChannelManager()
  }

  func onListen(withArguments arguments: Any?, eventSink events: @escaping FlutterEventSink) -> FlutterError? {
    eventSink = events
    emitStatus()
    return nil
  }

  func onCancel(withArguments arguments: Any?) -> FlutterError? {
    eventSink = nil
    return nil
  }

  private func handle(_ call: FlutterMethodCall, result: @escaping FlutterResult) {
    switch call.method {
    case "appVersion":
      let version = Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "-"
      let build = Bundle.main.object(forInfoDictionaryKey: "CFBundleVersion") as? String ?? "-"
      result("\(version) (\(build))")
    case "requestPermissions":
      AVAudioSession.sharedInstance().requestRecordPermission { granted in
        DispatchQueue.main.async { result(granted) }
      }
    case "connect":
      guard let values = call.arguments as? [String: Any] else {
        result(FlutterError(code: "INVALID_ARGUMENTS", message: "Missing connection settings", details: nil))
        return
      }
      endpoint = values["endpoint"] as? String ?? ""
      room = values["room"] as? String ?? "room-1"
      displayName = values["name"] as? String ?? "iPhone"
      authToken = values["token"] as? String ?? ""
      guard URL(string: endpoint) != nil, !authToken.isEmpty else {
        result(FlutterError(code: "INVALID_CONNECTION", message: "Server Address หรือ Token ไม่ถูกต้อง", details: nil))
        return
      }
      shouldReconnect = true
      saveConfiguration()
      joinSystemChannelAndConnect()
      result(nil)
    case "switchRoom":
      if let values = call.arguments as? [String: Any] {
        room = values["room"] as? String ?? room
        displayName = values["name"] as? String ?? displayName
      }
      saveConfiguration()
      updateSystemDescriptor()
      sendJSON(["type": "release-talk"])
      sendJoin()
      result(nil)
    case "requestTalk":
      guard connected else { result(nil); return }
      sendJSON(["type": "request-talk"])
      result(nil)
    case "releaseTalk":
      releaseTransmission()
      result(nil)
    case "disconnect":
      disconnect()
      result(nil)
    case "requestState":
      emitStatus()
      result(nil)
    case "cycleOutput":
      cycleOutput()
      result(nil)
    case "testSpeaker":
      AudioServicesPlaySystemSound(1104)
      result(nil)
    default:
      result(FlutterMethodNotImplemented)
    }
  }

  private func createChannelManager() {
    PTChannelManager.channelManager(delegate: self, restorationDelegate: self) { [weak self] manager, error in
      DispatchQueue.main.async {
        guard let self else { return }
        if let manager {
          self.channelManager = manager
          if self.shouldReconnect, manager.activeChannelUUID == Self.channelUUID, !self.endpoint.isEmpty {
            self.updateSystemDescriptor()
            self.connectWebSocket()
          }
        } else {
          self.emitStatus(text: "เปิดระบบ Push to Talk ไม่ได้: \(error?.localizedDescription ?? "unknown")")
        }
      }
    }
  }

  private func joinSystemChannelAndConnect() {
    guard let channelManager else {
      emitStatus(text: "กำลังเตรียม Push to Talk…")
      return
    }
    if channelManager.activeChannelUUID == Self.channelUUID {
      updateSystemDescriptor()
      connectWebSocket()
      return
    }
    let descriptor = PTChannelDescriptor(name: roomLabel(room), image: nil)
    channelManager.requestJoinChannel(channelUUID: Self.channelUUID, descriptor: descriptor)
    emitStatus(text: "กำลังเข้าห้อง…")
  }

  private func updateSystemDescriptor() {
    let descriptor = PTChannelDescriptor(name: roomLabel(room), image: nil)
    channelManager?.setChannelDescriptor(descriptor, channelUUID: Self.channelUUID) { [weak self] error in
      if let error { self?.emitStatus(text: "เปลี่ยนห้องในระบบไม่ได้: \(error.localizedDescription)") }
    }
  }

  private func connectWebSocket() {
    guard let url = URL(string: endpoint) else {
      emitStatus(text: "Server Address ไม่ถูกต้อง")
      return
    }
    closeWebSocket()
    var request = URLRequest(url: url)
    request.setValue("Bearer \(authToken)", forHTTPHeaderField: "Authorization")
    let session = URLSession(configuration: .default, delegate: self, delegateQueue: .main)
    let task = session.webSocketTask(with: request)
    webSocketSession = session
    webSocket = task
    task.resume()
    receiveNextMessage()
    pingTimer = Timer.scheduledTimer(withTimeInterval: 15, repeats: true) { [weak self] _ in
      self?.webSocket?.sendPing { error in
        if let error { self?.socketFailed(error) }
      }
    }
    emitStatus(text: "กำลังเชื่อมต่อ…")
  }

  private func receiveNextMessage() {
    webSocket?.receive { [weak self] result in
      DispatchQueue.main.async {
        guard let self else { return }
        switch result {
        case .success(let message):
          switch message {
          case .string(let text): self.handleServerText(text)
          case .data(let data): self.handleAudio(data)
          @unknown default: break
          }
          self.receiveNextMessage()
        case .failure(let error):
          self.socketFailed(error)
        }
      }
    }
  }

  private func handleServerText(_ text: String) {
    guard
      let data = text.data(using: .utf8),
      let message = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
      let type = message["type"] as? String
    else { return }

    switch type {
    case "authenticated":
      isAdmin = message["role"] as? String == "admin"
      if room == "all" && !isAdmin {
        emitStatus(text: "Token นี้ไม่มีสิทธิ์ใช้งาน All", event: "forbidden_all")
      } else {
        sendJoin()
      }
    case "joined":
      connected = true
      room = message["room"] as? String ?? room
      isAdmin = message["role"] as? String == "admin"
      registerPushToken()
      emitStatus(text: "พร้อมพูด")
    case "floor-granted":
      floorGranted = true
      channelManager?.requestBeginTransmitting(channelUUID: Self.channelUUID)
    case "floor-busy":
      floorGranted = false
      busy = true
      activeSpeaker = message["name"] as? String ?? "ผู้ใช้อื่น"
      if systemTransmitting { channelManager?.stopTransmitting(channelUUID: Self.channelUUID) }
      emitStatus(text: "ช่องสัญญาณไม่ว่าง", event: "floor_busy")
    case "speaker":
      updateRemoteSpeaker(message)
    case "members":
      updateMembers(message)
    case "error":
      let event = message["code"] as? String == "forbidden_all" ? "forbidden_all" : nil
      emitStatus(text: message["message"] as? String ?? "Server error", event: event)
    default:
      break
    }
  }

  private func updateRemoteSpeaker(_ message: [String: Any]) {
    let active = message["active"] as? Bool == true
    if active {
      busy = true
      activeSpeaker = message["name"] as? String ?? "ผู้ใช้อื่น"
      let participant = PTParticipant(name: activeSpeaker, image: nil)
      channelManager?.setActiveRemoteParticipant(participant, channelUUID: Self.channelUUID) { [weak self] error in
        if let error { self?.emitStatus(text: "เปิดเสียงรับไม่ได้: \(error.localizedDescription)") }
      }
      emitStatus(text: "กำลังพูด: \(activeSpeaker)")
    } else {
      busy = false
      activeSpeaker = ""
      pendingAudio.removeAll()
      stopPlayback()
      channelManager?.setActiveRemoteParticipant(nil, channelUUID: Self.channelUUID, completionHandler: nil)
      emitStatus(text: "พร้อมพูด")
    }
  }

  private func updateMembers(_ message: [String: Any]) {
    allMembersView = message["allView"] as? Bool == true
    let source = message["members"] as? [[String: Any]] ?? []
    members = source.map { member in
      let name = member["name"] as? String ?? "iPhone"
      let selfSuffix = member["self"] as? Bool == true ? " (คุณ)" : ""
      let roomSuffix = allMembersView ? " • \(roomLabel(member["room"] as? String ?? ""))" : ""
      return name + selfSuffix + roomSuffix
    }
    emitStatus(text: "อัปเดตรายชื่อออนไลน์")
  }

  private func sendJoin() {
    sendJSON([
      "type": "join",
      "room": room,
      "name": displayName,
      "device": UIDevice.current.model + " iOS " + UIDevice.current.systemVersion,
    ])
  }

  private func registerPushToken() {
    guard let token = ephemeralPushToken else { return }
    sendJSON(["type": "register-push", "token": token.map { String(format: "%02x", $0) }.joined()])
  }

  private func sendJSON(_ value: [String: Any]) {
    guard
      let data = try? JSONSerialization.data(withJSONObject: value),
      let text = String(data: data, encoding: .utf8)
    else { return }
    webSocket?.send(.string(text)) { [weak self] error in
      if let error { self?.socketFailed(error) }
    }
  }

  private func handleAudio(_ data: Data) {
    guard audioSessionActive else {
      pendingAudio.append(data)
      if pendingAudio.count > 25 { pendingAudio.removeFirst() }
      return
    }
    if playbackEngine == nil { startPlayback() }
    scheduleAudio(data)
  }

  private func startRecording() {
    guard floorGranted, systemTransmitting, recordEngine == nil else { return }
    let engine = AVAudioEngine()
    let input = engine.inputNode
    let format = input.inputFormat(forBus: 0)
    input.installTap(onBus: 0, bufferSize: 960, format: format) { [weak self] buffer, _ in
      guard let self, let channel = buffer.floatChannelData?[0] else { return }
      let sourceRate = format.sampleRate
      let frameCount = Int(buffer.frameLength)
      guard sourceRate > 0, frameCount > 0 else { return }
      let step = sourceRate / 16_000.0
      var position = 0.0
      var output = Data(capacity: Int(Double(frameCount) / step) * 2)
      while Int(position) < frameCount {
        let sample = max(-1.0, min(1.0, channel[Int(position)]))
        var pcm = Int16(sample * Float(Int16.max)).littleEndian
        withUnsafeBytes(of: &pcm) { output.append(contentsOf: $0) }
        position += step
      }
      self.webSocket?.send(.data(output)) { _ in }
    }
    do {
      engine.prepare()
      try engine.start()
      recordEngine = engine
      selfTalking = true
      emitStatus(text: "คุณกำลังพูด")
    } catch {
      input.removeTap(onBus: 0)
      emitStatus(text: "เปิดไมโครโฟนไม่ได้: \(error.localizedDescription)")
    }
  }

  private func stopRecording() {
    recordEngine?.inputNode.removeTap(onBus: 0)
    recordEngine?.stop()
    recordEngine = nil
    selfTalking = false
  }

  private func startPlayback() {
    let engine = AVAudioEngine()
    let node = AVAudioPlayerNode()
    guard let format = AVAudioFormat(commonFormat: .pcmFormatInt16, sampleRate: 16_000, channels: 1, interleaved: false) else { return }
    engine.attach(node)
    engine.connect(node, to: engine.mainMixerNode, format: format)
    do {
      engine.prepare()
      try engine.start()
      node.play()
      playbackEngine = engine
      playbackNode = node
      let queued = pendingAudio
      pendingAudio.removeAll()
      queued.forEach(scheduleAudio)
    } catch {
      emitStatus(text: "เปิดลำโพงไม่ได้: \(error.localizedDescription)")
    }
  }

  private func scheduleAudio(_ data: Data) {
    guard
      let node = playbackNode,
      let format = AVAudioFormat(commonFormat: .pcmFormatInt16, sampleRate: 16_000, channels: 1, interleaved: false),
      let buffer = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: AVAudioFrameCount(data.count / 2)),
      let destination = buffer.int16ChannelData?[0]
    else { return }
    buffer.frameLength = buffer.frameCapacity
    data.withUnsafeBytes { source in
      if let address = source.baseAddress { memcpy(destination, address, data.count) }
    }
    node.scheduleBuffer(buffer)
  }

  private func stopPlayback() {
    playbackNode?.stop()
    playbackEngine?.stop()
    playbackNode = nil
    playbackEngine = nil
  }

  private func releaseTransmission() {
    floorGranted = false
    stopRecording()
    channelManager?.stopTransmitting(channelUUID: Self.channelUUID)
    sendJSON(["type": "release-talk"])
    emitStatus(text: connected ? "พร้อมพูด" : "ยังไม่เชื่อมต่อ")
  }

  private func cycleOutput() {
    let session = AVAudioSession.sharedInstance()
    do {
      if outputLabel == "ลำโพง" {
        try session.overrideOutputAudioPort(.none)
        outputLabel = "อัตโนมัติ"
      } else {
        try session.overrideOutputAudioPort(.speaker)
        outputLabel = "ลำโพง"
      }
      emitStatus(text: "เปลี่ยนเป็น \(outputLabel)")
    } catch {
      emitStatus(text: "เปลี่ยนอุปกรณ์เสียงไม่ได้: \(error.localizedDescription)")
    }
  }

  private func disconnect() {
    shouldReconnect = false
    saveConfiguration()
    sendJSON(["type": "unregister-push"])
    releaseTransmission()
    closeWebSocket()
    channelManager?.leaveChannel(channelUUID: Self.channelUUID)
    resetConnection()
    emitStatus(text: "ตัดการเชื่อมต่อ")
  }

  private func closeWebSocket() {
    pingTimer?.invalidate()
    pingTimer = nil
    webSocket?.cancel(with: .normalClosure, reason: nil)
    webSocket = nil
    webSocketSession?.invalidateAndCancel()
    webSocketSession = nil
  }

  private func socketFailed(_ error: Error) {
    guard webSocket != nil else { return }
    closeWebSocket()
    resetConnection()
    emitStatus(text: "เชื่อมต่อไม่ได้: \(error.localizedDescription)")
  }

  private func resetConnection() {
    stopRecording()
    stopPlayback()
    connected = false
    busy = false
    selfTalking = false
    floorGranted = false
    systemTransmitting = false
    activeSpeaker = ""
    members = []
    allMembersView = false
    isAdmin = false
  }

  private func loadConfiguration() {
    let defaults = UserDefaults.standard
    endpoint = defaults.string(forKey: "ptt.native.endpoint") ?? ""
    room = defaults.string(forKey: "ptt.native.room") ?? "room-1"
    displayName = defaults.string(forKey: "ptt.native.name") ?? "iPhone"
    shouldReconnect = defaults.bool(forKey: "ptt.native.joined")
    authToken = readTokenFromKeychain()
  }

  private func saveConfiguration() {
    let defaults = UserDefaults.standard
    defaults.set(endpoint, forKey: "ptt.native.endpoint")
    defaults.set(room, forKey: "ptt.native.room")
    defaults.set(displayName, forKey: "ptt.native.name")
    defaults.set(shouldReconnect, forKey: "ptt.native.joined")
    saveTokenToKeychain(authToken)
  }

  private func readTokenFromKeychain() -> String {
    let query: [String: Any] = [
      kSecClass as String: kSecClassGenericPassword,
      kSecAttrService as String: (Bundle.main.bundleIdentifier ?? "ptt.flutter") + ".native",
      kSecAttrAccount as String: "relay-token",
      kSecReturnData as String: true,
      kSecMatchLimit as String: kSecMatchLimitOne,
    ]
    var item: CFTypeRef?
    guard SecItemCopyMatching(query as CFDictionary, &item) == errSecSuccess,
          let data = item as? Data,
          let value = String(data: data, encoding: .utf8) else { return "" }
    return value
  }

  private func saveTokenToKeychain(_ value: String) {
    let key: [String: Any] = [
      kSecClass as String: kSecClassGenericPassword,
      kSecAttrService as String: (Bundle.main.bundleIdentifier ?? "ptt.flutter") + ".native",
      kSecAttrAccount as String: "relay-token",
    ]
    SecItemDelete(key as CFDictionary)
    guard !value.isEmpty, let data = value.data(using: .utf8) else { return }
    var item = key
    item[kSecValueData as String] = data
    item[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
    SecItemAdd(item as CFDictionary, nil)
  }

  private func emitStatus(text: String? = nil, event: String? = nil) {
    DispatchQueue.main.async { [weak self] in
      guard let self else { return }
      var value: [String: Any] = [
        "status": text ?? (self.connected ? "เชื่อมต่ออยู่" : "ยังไม่เชื่อมต่อ"),
        "ready": self.connected,
        "output": self.outputLabel,
        "busy": self.busy,
        "speaker": self.activeSpeaker,
        "selfTalking": self.selfTalking,
        "members": self.members,
        "allMembersView": self.allMembersView,
        "admin": self.isAdmin,
        "activeRoom": self.connected ? self.room : "",
      ]
      if let event { value["event"] = event }
      self.eventSink?(value)
    }
  }

  private func roomLabel(_ value: String) -> String {
    if value == "all" { return "All" }
    if value.hasPrefix("room-") { return "ห้อง " + value.replacingOccurrences(of: "room-", with: "") }
    return value
  }
}

@available(iOS 16.0, *)
extension PttBridge: URLSessionWebSocketDelegate {
  func urlSession(
    _ session: URLSession,
    webSocketTask: URLSessionWebSocketTask,
    didOpenWithProtocol protocol: String?
  ) {
    emitStatus(text: "ตรวจสอบสิทธิ์กับ Server…")
  }

  func urlSession(
    _ session: URLSession,
    webSocketTask: URLSessionWebSocketTask,
    didCloseWith closeCode: URLSessionWebSocketTask.CloseCode,
    reason: Data?
  ) {
    guard webSocket === webSocketTask else { return }
    closeWebSocket()
    resetConnection()
    emitStatus(text: "ตัดการเชื่อมต่อ")
  }
}

@available(iOS 16.0, *)
extension PttBridge: PTChannelRestorationDelegate {
  func channelDescriptor(restoredChannelUUID channelUUID: UUID) -> PTChannelDescriptor {
    PTChannelDescriptor(name: roomLabel(room), image: nil)
  }
}

@available(iOS 16.0, *)
extension PttBridge: PTChannelManagerDelegate {
  func channelManager(_ channelManager: PTChannelManager, didJoinChannel channelUUID: UUID, reason: PTChannelJoinReason) {
    shouldReconnect = true
    saveConfiguration()
    channelManager.setTransmissionMode(.halfDuplex, channelUUID: channelUUID, completionHandler: nil)
    connectWebSocket()
  }

  func channelManager(_ channelManager: PTChannelManager, didLeaveChannel channelUUID: UUID, reason: PTChannelLeaveReason) {
    shouldReconnect = false
    saveConfiguration()
    closeWebSocket()
    resetConnection()
    emitStatus(text: "ออกจากห้องแล้ว")
  }

  func channelManager(_ channelManager: PTChannelManager, receivedEphemeralPushToken pushToken: Data) {
    ephemeralPushToken = pushToken
    registerPushToken()
  }

  func incomingPushResult(
    channelManager: PTChannelManager,
    channelUUID: UUID,
    pushPayload: [String: Any]
  ) -> PTPushResult {
    if webSocket == nil, !endpoint.isEmpty { connectWebSocket() }
    guard let speaker = pushPayload["activeSpeaker"] as? String, !speaker.isEmpty else {
      return .leaveChannel
    }
    activeSpeaker = speaker
    busy = true
    return .activeRemoteParticipant(PTParticipant(name: speaker, image: nil))
  }

  func channelManager(
    _ channelManager: PTChannelManager,
    channelUUID: UUID,
    didBeginTransmittingFrom source: PTChannelTransmitRequestSource
  ) {
    systemTransmitting = true
    if !floorGranted { sendJSON(["type": "request-talk"]) }
    if audioSessionActive, floorGranted { startRecording() }
  }

  func channelManager(
    _ channelManager: PTChannelManager,
    channelUUID: UUID,
    didEndTransmittingFrom source: PTChannelTransmitRequestSource
  ) {
    systemTransmitting = false
    floorGranted = false
    stopRecording()
    sendJSON(["type": "release-talk"])
    emitStatus(text: connected ? "พร้อมพูด" : "ยังไม่เชื่อมต่อ")
  }

  func channelManager(_ channelManager: PTChannelManager, didActivate audioSession: AVAudioSession) {
    audioSessionActive = true
    try? audioSession.setCategory(.playAndRecord, mode: .voiceChat, options: [.defaultToSpeaker, .allowBluetoothHFP])
    if systemTransmitting, floorGranted {
      startRecording()
    } else if busy {
      startPlayback()
    }
  }

  func channelManager(_ channelManager: PTChannelManager, didDeactivate audioSession: AVAudioSession) {
    audioSessionActive = false
    stopRecording()
    stopPlayback()
  }

  func channelManager(_ channelManager: PTChannelManager, failedToJoinChannel channelUUID: UUID, error: Error) {
    emitStatus(text: "เข้าห้อง iOS ไม่สำเร็จ: \(error.localizedDescription)")
  }

  func channelManager(
    _ channelManager: PTChannelManager,
    failedToBeginTransmittingInChannel channelUUID: UUID,
    error: Error
  ) {
    floorGranted = false
    systemTransmitting = false
    sendJSON(["type": "release-talk"])
    emitStatus(text: "เริ่มพูดไม่ได้: \(error.localizedDescription)")
  }
}
