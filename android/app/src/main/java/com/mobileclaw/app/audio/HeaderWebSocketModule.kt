package com.mobileclaw.app.audio

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.bridge.ReadableMap
import com.facebook.react.modules.core.DeviceEventManagerModule
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class HeaderWebSocketModule(
  private val reactContext: ReactApplicationContext,
) : ReactContextBaseJavaModule(reactContext) {

  private val httpClient: OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(15, TimeUnit.SECONDS)
    .readTimeout(0, TimeUnit.MILLISECONDS)
    .writeTimeout(15, TimeUnit.SECONDS)
    .build()

  private var webSocket: WebSocket? = null
  private var audioRecord: AudioRecord? = null
  private var captureThread: Thread? = null
  private val isCapturing = AtomicBoolean(false)
  private var lastSocketUrl: String? = null
  private var lastAudioError: String? = null

  override fun getName(): String = "HeaderWebSocket"

  @ReactMethod
  fun connect(url: String, headers: ReadableMap, promise: Promise) {
    try {
      closeInternal(1000, "reconnect")

      val headersBuilder = Headers.Builder()
      val iterator = headers.keySetIterator()
      while (iterator.hasNextKey()) {
        val key = iterator.nextKey()
        val value = headers.getString(key)
        if (!value.isNullOrBlank()) {
          headersBuilder.add(key, value)
        }
      }

      val request = Request.Builder()
        .url(url)
        .headers(headersBuilder.build())
        .build()

      lastSocketUrl = url
      webSocket = httpClient.newWebSocket(request, object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
          emitEvent("onOpen", null)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
          val payload = Arguments.createMap().apply {
            putString("type", "text")
            putString("data", text)
          }
          emitEvent("onMessage", payload)
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
          val array = Arguments.createArray()
          bytes.toByteArray().forEach { value ->
            array.pushInt(value.toInt() and 0xff)
          }
          val payload = Arguments.createMap().apply {
            putString("type", "binary")
            putArray("data", array)
          }
          emitEvent("onMessage", payload)
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
          emitClose(code, reason)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
          emitClose(code, reason)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
          val payload = Arguments.createMap().apply {
            putInt("code", response?.code ?: -1)
            putString("message", t.message ?: "WebSocket failure")
          }
          emitEvent("onError", payload)
          emitClose(response?.code ?: -1, t.message ?: "WebSocket failure")
        }
      })

      promise.resolve(true)
    } catch (error: Throwable) {
      promise.reject("CONNECT_FAILED", error.message, error)
    }
  }

  @ReactMethod
  fun sendData(data: com.facebook.react.bridge.ReadableArray, promise: Promise) {
    try {
      val socket = webSocket ?: throw IllegalStateException("WebSocket not connected")
      val bytes = ByteArray(data.size())
      for (i in 0 until data.size()) {
        bytes[i] = (data.getInt(i) and 0xff).toByte()
      }
      val ok = socket.send(ByteString.of(*bytes))
      if (!ok) {
        throw IllegalStateException("WebSocket send returned false")
      }
      promise.resolve(true)
    } catch (error: Throwable) {
      promise.reject("SEND_FAILED", error.message, error)
    }
  }

  @ReactMethod
  fun close(promise: Promise) {
    closeInternal(1000, "closed-by-js")
    promise.resolve(true)
  }

  @ReactMethod
  fun startAudioCapture(promise: Promise) {
    try {
      if (isCapturing.get()) {
        promise.resolve(true)
        return
      }

      if (!hasRecordAudioPermission()) {
        throw IllegalStateException("RECORD_AUDIO permission not granted")
      }

      val sampleRate = 16_000
      val channelConfig = AudioFormat.CHANNEL_IN_MONO
      val encoding = AudioFormat.ENCODING_PCM_16BIT
      val minBuffer = AudioRecord.getMinBufferSize(sampleRate, channelConfig, encoding)
      if (minBuffer <= 0) {
        throw IllegalStateException("AudioRecord.getMinBufferSize failed: $minBuffer")
      }

      val bufferSize = maxOf(minBuffer * 2, 4096)
      val recorder = AudioRecord(
        MediaRecorder.AudioSource.VOICE_RECOGNITION,
        sampleRate,
        channelConfig,
        encoding,
        bufferSize,
      )

      if (recorder.state != AudioRecord.STATE_INITIALIZED) {
        recorder.release()
        throw IllegalStateException("AudioRecord failed to initialize")
      }

      audioRecord = recorder
      lastAudioError = null
      recorder.startRecording()
      isCapturing.set(true)

      emitAudioStatus("started", bufferSize, sampleRate)

      captureThread = Thread {
        val buffer = ByteArray(2048)
        try {
          while (isCapturing.get()) {
            val read = recorder.read(buffer, 0, buffer.size)
            if (read > 0) {
              val payload = Arguments.createArray()
              for (i in 0 until read) {
                payload.pushInt(buffer[i].toInt() and 0xff)
              }
              val body = Arguments.createMap().apply {
                putArray("data", payload)
              }
              emitEvent("onAudioData", body)
            } else if (read < 0) {
              val message = "AudioRecord read failed: $read"
              lastAudioError = message
              emitAudioError(message)
              break
            }
          }
        } catch (error: Throwable) {
          lastAudioError = error.message ?: "Audio capture crashed"
          emitAudioError(lastAudioError ?: "Audio capture crashed")
        } finally {
          stopAudioCaptureInternal()
        }
      }.apply {
        name = "MobileClaw-AudioCapture"
        start()
      }

      promise.resolve(true)
    } catch (error: Throwable) {
      lastAudioError = error.message
      emitAudioError(error.message ?: "Audio capture failed")
      promise.reject("AUDIO_CAPTURE_FAILED", error.message, error)
    }
  }

  @ReactMethod
  fun stopAudioCapture(promise: Promise) {
    stopAudioCaptureInternal()
    promise.resolve(true)
  }

  @ReactMethod
  fun getAudioCaptureDebugInfo(promise: Promise) {
    val map = Arguments.createMap().apply {
      putBoolean("isCapturing", isCapturing.get())
      putString("socketUrl", lastSocketUrl ?: "")
      putString("lastAudioError", lastAudioError ?: "")
      putBoolean("hasPermission", hasRecordAudioPermission())
    }
    promise.resolve(map)
  }

  @ReactMethod
  fun addListener(eventName: String) {
  }

  @ReactMethod
  fun removeListeners(count: Int) {
  }

  private fun closeInternal(code: Int, reason: String) {
    try {
      webSocket?.close(code, reason)
    } catch (_: Throwable) {
    } finally {
      webSocket = null
    }
  }

  private fun stopAudioCaptureInternal() {
    if (!isCapturing.getAndSet(false)) return

    try {
      audioRecord?.stop()
    } catch (_: Throwable) {
    }

    try {
      audioRecord?.release()
    } catch (_: Throwable) {
    }
    audioRecord = null

    if (captureThread?.isAlive == true && Thread.currentThread() !== captureThread) {
      try {
        captureThread?.join(300)
      } catch (_: InterruptedException) {
      }
    }
    captureThread = null
    emitAudioStatus("stopped", null, 16_000)
  }

  private fun hasRecordAudioPermission(): Boolean {
    return ContextCompat.checkSelfPermission(
      reactContext,
      Manifest.permission.RECORD_AUDIO,
    ) == PackageManager.PERMISSION_GRANTED
  }

  private fun emitAudioStatus(status: String, bufferSize: Int?, sampleRate: Int) {
    val payload = Arguments.createMap().apply {
      putString("status", status)
      putInt("sampleRate", sampleRate)
      if (bufferSize != null) {
        putInt("bufferSize", bufferSize)
      }
    }
    emitEvent("onAudioCaptureStatus", payload)
  }

  private fun emitAudioError(message: String) {
    val payload = Arguments.createMap().apply {
      putString("message", message)
    }
    emitEvent("onAudioCaptureError", payload)
  }

  private fun emitClose(code: Int, reason: String) {
    val payload = Arguments.createMap().apply {
      putInt("code", code)
      putString("reason", reason)
    }
    emitEvent("onClose", payload)
  }

  private fun emitEvent(name: String, params: Any?) {
    reactContext
      .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
      .emit(name, params)
  }
}
