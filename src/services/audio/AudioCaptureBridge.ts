/**
 * AudioCaptureBridge — Bridges mic input to ASR provider
 *
 * Captures real-time PCM audio from microphone and feeds it to
 * the active ASR service via asrService.feedPCM().
 *
 * Phase 1: Infrastructure ready, awaiting native PCM source.
 * See inline docs for upgrade path.
 */

import { audioManager } from './AudioManager';
import { asrService } from './ASRService';
import { getLogger } from '@/utils/logger';

const log = getLogger('AudioCaptureBridge');

class AudioCaptureBridge {
  private isCapturing = false;

  /**
   * Start capturing audio and feeding PCM to ASR.
   */
  async startCapture(): Promise<void> {
    if (this.isCapturing) return;

    log.info('Starting audio capture bridge...');

    // Ensure audio session is configured
    if (!audioManager.isConfigured()) {
      await audioManager.configureSession();
    }

    // Start mic recording (for waveform visualization)
    await audioManager.startRecording();

    this.isCapturing = true;
    log.info('Audio capture bridge started (infrastructure ready)');
  }

  /**
   * Stop capturing audio.
   */
  async stopCapture(): Promise<void> {
    if (!this.isCapturing) return;

    this.isCapturing = false;

    // Stop mic recording
    await audioManager.stopRecording();

    log.info('Audio capture stopped');
  }

  getIsCapturing(): boolean {
    return this.isCapturing;
  }
}

export const audioCaptureBridge = new AudioCaptureBridge();
