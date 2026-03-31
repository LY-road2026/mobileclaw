/**
 * AudioManager — Audio session configuration + recording lifecycle
 *
 * Uses expo-av v16 for:
 *  - AVAudioSession / AudioManager platform configuration
 *  - Simultaneous record + playback (playAndRecord + voiceChat)
 *  - Audio recording with volume monitoring for waveform visualization
 */

import { Audio } from 'expo-av';
import { Platform } from 'react-native';
import { getLogger } from '@/utils/logger';

const log = getLogger('AudioManager');

export interface AudioSessionConfig {
  allowsRecordingIOS?: boolean;
  playsInSilentModeIOS?: boolean;
  interruptionModeIOS?: number;   // InterruptionModeIOS enum value
  shouldDuckAndroid?: boolean;
  interruptionModeAndroid?: number; // InterruptionModeAndroid enum value
}

const DEFAULT_SESSION_CONFIG: AudioSessionConfig = {
  allowsRecordingIOS: true,
  playsInSilentModeIOS: true,
  interruptionModeIOS: 1, // InterruptionModeIOS.DoNotMix
  shouldDuckAndroid: true,
  interruptionModeAndroid: 1, // InterruptionModeAndroid.DoNotMix
};

export class AudioManager {
  private configured = false;
  private recording: Audio.Recording | null = null;
  private isRecording = false;

  // Volume level callback (for waveform visualization)
  private volumeListeners: Set<(level: number) => void> = new Set();
  private volumeMonitorInterval: ReturnType<typeof setInterval> | null = null;

  /**
   * Configure audio session for simultaneous recording + playback.
   */
  async configureSession(config?: Partial<AudioSessionConfig>): Promise<void> {
    const cfg = { ...DEFAULT_SESSION_CONFIG, ...config };

    log.info('Configuring audio session...', Platform.OS);

    await Audio.setAudioModeAsync({
      allowsRecordingIOS: cfg.allowsRecordingIOS ?? true,
      playsInSilentModeIOS: cfg.playsInSilentModeIOS ?? true,
      staysActiveInBackground: false,
      interruptionModeIOS: cfg.interruptionModeIOS ?? 1, // DoNotMix
      shouldDuckAndroid: cfg.shouldDuckAndroid ?? true,
      interruptionModeAndroid: cfg.interruptionModeAndroid ?? 1, // DoNotMix
    } as any); // Cast to satisfy v16 type checking

    this.configured = true;
    log.info('Audio session configured successfully');
  }

  isConfigured(): boolean {
    return this.configured;
  }

  /**
   * Start PCM audio recording.
   */
  async startRecording(): Promise<Audio.Recording> {
    if (this.isRecording) {
      log.warn('Already recording, ignoring startRecording()');
      return this.recording!;
    }

    log.info('Starting audio recording...');

    try {
      const permission = await Audio.requestPermissionsAsync();
      if (permission.status !== 'granted') {
        throw new Error('Microphone permission denied');
      }

      const recording = new Audio.Recording();
      await recording.prepareToRecordAsync(Audio.RecordingOptionsPresets.HighQuality);
      await recording.startAsync();

      this.recording = recording;
      this.isRecording = true;

      // Start volume monitoring for waveform
      this.startVolumeMonitoring();

      log.info('Recording started successfully');
      return recording;
    } catch (error) {
      log.error('Failed to start recording:', error);
      throw error;
    }
  }

  /**
   * Stop current recording and return the recorded file URI.
   */
  async stopRecording(): Promise<string | null> {
    if (!this.recording || !this.isRecording) return null;

    this.stopVolumeMonitoring();

    try {
      await this.recording.stopAndUnloadAsync();
      const uri = this.recording.getURI();
      log.info('Recording stopped, URI:', uri);
      this.isRecording = false;
      this.recording = null;
      return uri;
    } catch (error) {
      log.error('Error stopping recording:', error);
      this.isRecording = false;
      this.recording = null;
      return null;
    }
  }

  /** Check if currently recording */
  getIsRecording(): boolean {
    return this.isRecording;
  }

  /**
   * Register a listener for volume level updates (for WaveformView).
   */
  onVolumeUpdate(listener: (level: number) => void): () => void {
    this.volumeListeners.add(listener);
    return () => this.volumeListeners.delete(listener);
  }

  // ─── Internal: Volume Monitoring ───────────────────────────────────

  private startVolumeMonitoring(): void {
    this.stopVolumeMonitoring();

    this.volumeMonitorInterval = setInterval(async () => {
      if (!this.recording || !this.isRecording) return;

      try {
        const status = await this.recording.getStatusAsync() as Record<string, unknown>;
        if (status.isLoaded && 'metering' in status) {
          const metering = status.metering as number;
          const level = Math.max(0, Math.min(1, (metering + 60) / 60));
          this.volumeListeners.forEach((fn) => fn(level));
        }
      } catch {
        // Silently skip failed status reads
      }
    }, 50);
  }

  private stopVolumeMonitoring(): void {
    if (this.volumeMonitorInterval) {
      clearInterval(this.volumeMonitorInterval);
      this.volumeMonitorInterval = null;
    }
  }

  destroy(): void {
    this.stopVolumeMonitoring();
    if (this.isRecording && this.recording) {
      this.recording.stopAndUnloadAsync().catch(() => {});
      this.isRecording = false;
      this.recording = null;
    }
  }
}

export const audioManager = new AudioManager();
