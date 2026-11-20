/*
 * Adapted from Meta's CameraAccess sample (VideoFrameDecoder.swift),
 * Copyright (c) Meta Platforms, Inc. and affiliates, used under the
 * Meta Wearables Developer Terms. Changed to return CVPixelBuffers so frames can
 * be published to LiveKit and shown on screen without an extra image conversion.
 */

import CoreMedia
import CoreVideo
import VideoToolbox
import os

/// Decompresses the glasses' HEVC frames into BGRA pixel buffers.
/// Survives backgrounding (software decode) and recovers on the next keyframe after
/// errors or resolution changes, which happen when the Bluetooth link ladders down.
final class PixelDecoder: @unchecked Sendable {
  private struct State {
    var session: VTDecompressionSession?
    var formatDescription: CMFormatDescription?
    var consecutiveFailures = 0
    var awaitingKeyframe = false
    var lastGood: CVPixelBuffer?
  }

  private let state = OSAllocatedUnfairLock(uncheckedState: State())

  /// Returns a decoded frame, or nil while waiting for a keyframe.
  func decode(_ sampleBuffer: CMSampleBuffer) -> CVPixelBuffer? {
    // Already-decoded frames (mock device, raw codec) pass straight through.
    if let image = CMSampleBufferGetImageBuffer(sampleBuffer) { return image }
    guard CMSampleBufferGetDataBuffer(sampleBuffer) != nil,
      let format = CMSampleBufferGetFormatDescription(sampleBuffer)
    else { return nil }
    let isKeyframe = sampleBuffer.isHEVCKeyframe()

    let (session, awaiting) = state.withLockUnchecked { s -> (VTDecompressionSession?, Bool) in
      if let current = s.formatDescription, let existing = s.session, !CMFormatDescriptionEqual(current, otherFormatDescription: format) {
        VTDecompressionSessionInvalidate(existing)
        s.session = nil
        s.formatDescription = nil
        s.awaitingKeyframe = true
      }
      if let existing = s.session {
        if VTDecompressionSessionCanAcceptFormatDescription(existing, formatDescription: format) {
          return (existing, s.awaitingKeyframe)
        }
        VTDecompressionSessionInvalidate(existing)
        s.session = nil
        s.formatDescription = nil
        s.awaitingKeyframe = true
      }
      let spec = [kVTVideoDecoderSpecification_EnableHardwareAcceleratedVideoDecoder as String: false] as CFDictionary
      let attrs: [CFString: Any] = [
        kCVPixelBufferPixelFormatTypeKey: kCVPixelFormatType_32BGRA,
        kCVPixelBufferIOSurfacePropertiesKey: [:] as CFDictionary,
      ]
      var created: VTDecompressionSession?
      VTDecompressionSessionCreate(
        allocator: kCFAllocatorDefault, formatDescription: format, decoderSpecification: spec,
        imageBufferAttributes: attrs as CFDictionary, outputCallback: nil, decompressionSessionOut: &created)
      s.session = created
      s.formatDescription = format
      s.consecutiveFailures = 0
      s.awaitingKeyframe = true
      return (created, true)
    }

    if awaiting && !isKeyframe { return nil }
    guard let session else { return nil }

    var output: CVPixelBuffer?
    let status = VTDecompressionSessionDecodeFrame(session, sampleBuffer: sampleBuffer, flags: [], infoFlagsOut: nil) { status, _, imageBuffer, _, _ in
      if status == noErr, let imageBuffer { output = imageBuffer }
    }
    if status != noErr {
      state.withLockUnchecked { s in
        s.consecutiveFailures += 1
        if s.consecutiveFailures >= 3 {
          if let sess = s.session { VTDecompressionSessionInvalidate(sess) }
          s.session = nil
          s.formatDescription = nil
          s.consecutiveFailures = 0
          s.awaitingKeyframe = true
        }
      }
      return nil
    }
    state.withLockUnchecked { s in
      s.consecutiveFailures = 0
      if isKeyframe { s.awaitingKeyframe = false }
      if let output { s.lastGood = output }
    }
    return output
  }

  func reset() {
    state.withLockUnchecked { s in
      if let sess = s.session { VTDecompressionSessionInvalidate(sess) }
      s = State()
    }
  }
}
