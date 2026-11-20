/*
 * Based on Meta's CameraAccess sample (VideoRecorder.swift), Copyright (c) Meta
 * Platforms, Inc. and affiliates, used under the Meta Wearables Developer Terms.
 * Changed to write video-only passthrough segments that roll over every few
 * minutes, so a crash, dead battery or killed app loses at most one segment.
 * Audio is recorded separately (AudioFileRecorder) from the call's microphone.
 */

import AVFoundation
import CoreMedia
import os

final class SegmentRecorder: @unchecked Sendable {
  struct Segment: Codable, Equatable {
    let url: URL
    let index: Int
    let startedAt: Date
    var endedAt: Date?
  }

  private struct State {
    var writer: AVAssetWriter?
    var handler: VideoCaptureHandler?
    var current: Segment?
    var accepting = false
    var segmentIndex = 0
    var finishing = false
  }

  private let state = OSAllocatedUnfairLock(uncheckedState: State())
  private let directory: URL
  private let segmentSeconds: TimeInterval
  private let prefix: String
  /// Called with each finished segment file, from a background thread.
  var onSegmentFinished: (@Sendable (Segment) -> Void)?

  init(directory: URL, prefix: String, segmentSeconds: TimeInterval = 300) {
    self.directory = directory
    self.prefix = prefix
    self.segmentSeconds = segmentSeconds
    try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
  }

  var isRecording: Bool { state.withLockUnchecked { $0.writer != nil } }

  func start() { state.withLockUnchecked { $0.accepting = true } }

  /// Stops accepting frames and finalizes the current segment.
  func stop() async {
    state.withLockUnchecked { $0.accepting = false }
    await finishCurrent()
  }

  /// Pause for privacy: finalize the current file so nothing after this moment exists.
  func pause() async { await stop() }

  func append(_ sampleBuffer: CMSampleBuffer) {
    let (accepting, writer, current, finishing) = state.withLockUnchecked { ($0.accepting, $0.writer, $0.current, $0.finishing) }
    guard accepting, !finishing else { return }
    // Passthrough writing needs the compressed (hvc1) sample.
    guard CMSampleBufferGetDataBuffer(sampleBuffer) != nil else { return }
    if writer == nil {
      guard sampleBuffer.isHEVCKeyframe() else { return }
      openSegment(with: sampleBuffer)
    } else if let current, Date().timeIntervalSince(current.startedAt) >= segmentSeconds, sampleBuffer.isHEVCKeyframe() {
      // Roll over on a keyframe so the next file opens cleanly.
      Task { await self.finishCurrent() }
      return
    }
    state.withLockUnchecked { $0.handler }?.appendVideoFrame(sampleBuffer)
  }

  private func openSegment(with sampleBuffer: CMSampleBuffer) {
    guard let format = CMSampleBufferGetFormatDescription(sampleBuffer) else { return }
    let dims = CMVideoFormatDescriptionGetDimensions(format)
    guard dims.width > 0, dims.height > 0 else { return }
    let index = state.withLockUnchecked { s -> Int in
      s.segmentIndex += 1
      return s.segmentIndex
    }
    let url = directory.appendingPathComponent(String(format: "%@-video-%03d.mov", prefix, index))
    try? FileManager.default.removeItem(at: url)
    do {
      let writer = try AVAssetWriter(outputURL: url, fileType: .mov)
      let handler = VideoCaptureHandler(writer: writer, width: Int(dims.width), height: Int(dims.height), sourceFormatHint: format)
      guard writer.startWriting() else { return }
      writer.startSession(atSourceTime: .zero)
      handler.start()
      state.withLockUnchecked { s in
        s.writer = writer
        s.handler = handler
        s.current = Segment(url: url, index: index, startedAt: Date())
      }
    } catch {
      NSLog("[Recorder] could not open segment: %@", error.localizedDescription)
    }
  }

  private func finishCurrent() async {
    let (writer, handler, segment) = state.withLockUnchecked { s -> (AVAssetWriter?, VideoCaptureHandler?, Segment?) in
      guard !s.finishing, let w = s.writer else { return (nil, nil, nil) }
      s.finishing = true
      return (w, s.handler, s.current)
    }
    guard let writer, var segment else { return }
    handler?.stop()
    await writer.finishWriting()
    state.withLockUnchecked { s in
      s.writer = nil
      s.handler = nil
      s.current = nil
      s.finishing = false
    }
    segment.endedAt = Date()
    if writer.status == .completed {
      onSegmentFinished?(segment)
    } else {
      NSLog("[Recorder] segment %d failed: %@", segment.index, writer.error?.localizedDescription ?? "unknown")
    }
  }
}
