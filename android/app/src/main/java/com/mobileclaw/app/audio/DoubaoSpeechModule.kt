package com.mobileclaw.app.audio

import android.app.Application
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.bridge.ReadableMap
import com.facebook.react.modules.core.DeviceEventManagerModule
import org.json.JSONObject
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy

/**
 * Android bridge for Doubao BiTTS.
 *
 * This implementation intentionally uses reflection for the ByteDance SpeechEngine
 * types so the repository can carry the Android integration code even on machines
 * that do not currently have the SDK indexed or compiled locally.
 */
class DoubaoSpeechModule(
  reactContext: ReactApplicationContext
) : ReactContextBaseJavaModule(reactContext) {

  private var engine: Any? = null
  private var config: Map<String, Any?> = emptyMap()
  private var listenerProxy: Any? = null
  private var engineStarted = false
  private var sessionOpen = false

  override fun getName(): String = "DoubaoSpeechModule"

  @ReactMethod
  fun initializeDoubaoTTS(configMap: ReadableMap, promise: Promise) {
    try {
      config = readableMapToMap(configMap)
      ensureEngineInitialized()
      promise.resolve(true)
    } catch (error: Throwable) {
      emitError("初始化 Android 豆包 TTS 失败: ${error.message}", errorCode = -1)
      promise.reject("ANDROID_TTS_INIT_FAILED", error)
    }
  }

  @ReactMethod
  fun speakDoubaoTTS(text: String, promise: Promise) {
    if (text.isBlank()) {
      promise.resolve(true)
      return
    }

    try {
      ensureEngineInitialized()
      val currentEngine = engine ?: error("SpeechEngine not initialized")
      val startPayload = buildStartPayload()

      sendDirective(currentEngine, "DIRECTIVE_SYNC_STOP_ENGINE", "")
      engineStarted = false
      sessionOpen = false

      sendDirective(currentEngine, "DIRECTIVE_START_ENGINE", startPayload)
      engineStarted = true
      emitStatus("engine_started")

      sendDirective(currentEngine, "DIRECTIVE_EVENT_START_SESSION", "")
      sessionOpen = true
      emitStatus("session_started")

      val requestJson = JSONObject()
        .put("req_params", JSONObject().put("text", text))
        .toString()

      sendDirective(currentEngine, "DIRECTIVE_EVENT_TASK_REQUEST", requestJson)
      emitStatus("request_sent")

      sendDirective(currentEngine, "DIRECTIVE_EVENT_FINISH_SESSION", "")
      emitStatus("finishing")

      promise.resolve(true)
    } catch (error: Throwable) {
      emitError("Android 豆包 TTS 合成失败: ${error.message}", errorCode = -2)
      promise.reject("ANDROID_TTS_SPEAK_FAILED", error)
    }
  }

  @ReactMethod
  fun stopDoubaoTTS(promise: Promise) {
    try {
      engine?.let {
        if (sessionOpen) {
          runCatching { sendDirective(it, "DIRECTIVE_EVENT_CANCLE_SESSION", "") }
          sessionOpen = false
        }
        if (engineStarted) {
          runCatching { sendDirective(it, "DIRECTIVE_SYNC_STOP_ENGINE", "") }
          engineStarted = false
        }
      }
      emitStatus("stopped")
      promise.resolve(true)
    } catch (error: Throwable) {
      promise.reject("ANDROID_TTS_STOP_FAILED", error)
    }
  }

  @ReactMethod
  fun destroyDoubaoTTS(promise: Promise) {
    try {
      val currentEngine = engine
      if (currentEngine != null) {
        runCatching {
          currentEngine.javaClass.getMethod("destroyEngine").invoke(currentEngine)
        }
      }
      engine = null
      listenerProxy = null
      engineStarted = false
      sessionOpen = false
      promise.resolve(true)
    } catch (error: Throwable) {
      promise.reject("ANDROID_TTS_DESTROY_FAILED", error)
    }
  }

  @ReactMethod
  fun addListener(eventName: String) {
    // Required for RN event emitter compatibility.
  }

  @ReactMethod
  fun removeListeners(count: Int) {
    // Required for RN event emitter compatibility.
  }

  private fun ensureEngineInitialized() {
    if (engine != null) return

    val generatorClass = classForName("com.bytedance.speech.speechengine.SpeechEngineGenerator")
    val speechEngine = invokeStatic(generatorClass, listOf("getInstance"))
    invokeInstance(speechEngine, listOf("createEngine"))

    prepareEnvironment(generatorClass)

    val currentEngine = speechEngine
    setListenerIfPossible(currentEngine)
    configureEngine(currentEngine)

    val initRet = invokeInt(currentEngine, "initEngine")
    if (initRet != 0) {
      error("SpeechEngine init failed: $initRet")
    }

    runCatching {
      invokeInstance(currentEngine, listOf("setContext"), reactApplicationContext.applicationContext)
    }

    engine = currentEngine
    emitStatus("initialized")
  }

  private fun prepareEnvironment(generatorClass: Class<*>) {
    val application = reactApplicationContext.applicationContext as? Application
      ?: return

    val candidates = listOf("PrepareEnvironment", "prepareEnvironment")
    var lastError: Throwable? = null
    for (name in candidates) {
      try {
        val method = generatorClass.methods.firstOrNull { method ->
          method.name == name && method.parameterTypes.size == 2
        } ?: continue
        method.invoke(null, reactApplicationContext.applicationContext, application)
        return
      } catch (error: Throwable) {
        lastError = error
      }
    }

    if (lastError != null) {
      throw lastError
    }
  }

  private fun configureEngine(currentEngine: Any) {
    val definesClass = classForName("com.bytedance.speech.speechengine.SpeechEngineDefines")

    setOptionString(currentEngine, definesClass, "PARAMS_KEY_ENGINE_NAME_STRING", getStaticField(definesClass, "BITTS_ENGINE") as? String ?: "BITTS_ENGINE")
    setOptionString(currentEngine, definesClass, "PARAMS_KEY_APP_ID_STRING", configString("appId"))
    setOptionString(currentEngine, definesClass, "PARAMS_KEY_APP_TOKEN_STRING", configString("accessToken"))
    setOptionString(currentEngine, definesClass, "PARAMS_KEY_TTS_ADDRESS_STRING", configString("address", "wss://openspeech.bytedance.com"))
    setOptionString(currentEngine, definesClass, "PARAMS_KEY_TTS_URI_STRING", configString("uri", "/api/v3/tts/bidirection"))
    setOptionString(currentEngine, definesClass, "PARAMS_KEY_RESOURCE_ID_STRING", configString("resourceId", "volc.service_type.10029"))
    setOptionString(currentEngine, definesClass, "PARAMS_KEY_START_ENGINE_PAYLOAD_STRING", buildStartPayload())
    setOptionString(currentEngine, definesClass, "PARAMS_KEY_REQUEST_HEADERS_STRING", "{}")
    setOptionBoolean(currentEngine, definesClass, "PARAMS_KEY_TTS_ENABLE_PLAYER_BOOL", true)
    setOptionBoolean(currentEngine, definesClass, "PARAMS_KEY_ENABLE_PLAYER_AUDIO_CALLBACK_BOOL", true)
    setOptionInt(currentEngine, definesClass, "PARAMS_KEY_TTS_CONN_TIMEOUT_INT", 10000)

    val logLevel = runCatching { getStaticField(definesClass, "LOG_LEVEL_WARN") as? String }.getOrNull() ?: "WARN"
    setOptionString(currentEngine, definesClass, "PARAMS_KEY_LOG_LEVEL_STRING", logLevel)
    setOptionString(currentEngine, definesClass, "PARAMS_KEY_DEBUG_PATH_STRING", "")
  }

  private fun setListenerIfPossible(currentEngine: Any) {
    val listenerClassNames = listOf(
      "com.bytedance.speech.speechengine.SpeechEngineListener",
      "com.bytedance.speech.speechengine.SpeechEngineCallback",
      "com.bytedance.speech.speechengine.ISpeechEngineListener",
    )

    val listenerClass = listenerClassNames.firstNotNullOfOrNull { name ->
      runCatching { classForName(name) }.getOrNull()
    } ?: return

    val proxy = Proxy.newProxyInstance(
      listenerClass.classLoader,
      arrayOf(listenerClass),
      SpeechInvocationHandler(),
    )

    listenerProxy = proxy
    runCatching {
      invokeInstance(currentEngine, listOf("setListener", "createEngineWithDelegate"), proxy)
    }
  }

  private fun sendDirective(currentEngine: Any, directiveFieldName: String, payload: String) {
    val definesClass = classForName("com.bytedance.speech.speechengine.SpeechEngineDefines")
    val directive = (getStaticField(definesClass, directiveFieldName) as? Int)
      ?: error("Missing directive constant: $directiveFieldName")
    val ret = invokeInstance(currentEngine, listOf("sendDirective"), directive, payload)
    if ((ret as? Int ?: 0) != 0) {
      error("sendDirective failed: $directiveFieldName -> $ret")
    }
  }

  private fun setOptionString(currentEngine: Any, definesClass: Class<*>, keyField: String, value: String) {
    val key = getStaticField(definesClass, keyField) as? String ?: return
    invokeInstance(currentEngine, listOf("setOptionString", "setStringParam"), key, value)
  }

  private fun setOptionInt(currentEngine: Any, definesClass: Class<*>, keyField: String, value: Int) {
    val key = getStaticField(definesClass, keyField) as? String ?: return
    invokeInstance(currentEngine, listOf("setOptionInt", "setIntParam"), key, value)
  }

  private fun setOptionBoolean(currentEngine: Any, definesClass: Class<*>, keyField: String, value: Boolean) {
    val key = getStaticField(definesClass, keyField) as? String ?: return
    invokeInstance(currentEngine, listOf("setOptionBoolean", "setBoolParam"), key, value)
  }

  private fun buildStartPayload(): String {
    val speaker = configString("voiceType", configString("voiceId", "zh_female_vv_uranus_bigtts"))
    return JSONObject()
      .put("user", JSONObject().put("uid", "mobileclaw-android"))
      .put(
        "req_params",
        JSONObject()
          .put("speaker", speaker)
          .put("audio_params", JSONObject())
      )
      .toString()
  }

  private fun configString(key: String, fallback: String = ""): String {
    return (config[key] as? String)?.takeIf { it.isNotBlank() } ?: fallback
  }

  private inner class SpeechInvocationHandler : InvocationHandler {
    override fun invoke(proxy: Any?, method: Method, args: Array<out Any?>?): Any? {
      if (method.name == "onSpeechMessage" && args != null && args.size >= 3) {
        val type = args[0] as? Int ?: 0
        val bytes = args[1] as? ByteArray ?: ByteArray(0)
        val payload = runCatching { String(bytes) }.getOrDefault("")
        handleSpeechMessage(type, payload)
      }
      return null
    }
  }

  private fun handleSpeechMessage(type: Int, payload: String) {
    val definesClass = runCatching { classForName("com.bytedance.speech.speechengine.SpeechEngineDefines") }.getOrNull()
      ?: return

    when (type) {
      getStaticInt(definesClass, "MESSAGE_TYPE_ENGINE_START") -> emitStatus("engine_started")
      getStaticInt(definesClass, "MESSAGE_TYPE_ENGINE_STOP") -> {
        engineStarted = false
        sessionOpen = false
        emitStatus("stopped")
      }
      getStaticInt(definesClass, "MESSAGE_TYPE_EVENT_TTS_SENTENCE_START"),
      getStaticInt(definesClass, "MESSAGE_TYPE_PLAYER_START_PLAY_AUDIO") -> emitStatus("playing")
      getStaticInt(definesClass, "MESSAGE_TYPE_EVENT_TTS_SENTENCE_END"),
      getStaticInt(definesClass, "MESSAGE_TYPE_EVENT_TTS_ENDED"),
      getStaticInt(definesClass, "MESSAGE_TYPE_PLAYER_FINISH_PLAY_AUDIO") -> emitStatus("finished")
      getStaticInt(definesClass, "MESSAGE_TYPE_ENGINE_ERROR") -> emitError(payload.ifBlank { "Android 豆包 TTS 原生错误" }, -3)
    }
  }

  private fun classForName(name: String): Class<*> = Class.forName(name)

  private fun getStaticField(clazz: Class<*>, fieldName: String): Any? {
    return clazz.getField(fieldName).get(null)
  }

  private fun getStaticInt(clazz: Class<*>, fieldName: String): Int {
    return runCatching { clazz.getField(fieldName).getInt(null) }.getOrDefault(Int.MIN_VALUE)
  }

  private fun invokeStatic(clazz: Class<*>, names: List<String>, vararg args: Any?): Any {
    val method = clazz.methods.firstOrNull { candidate ->
      candidate.name in names && candidate.parameterTypes.size == args.size
    } ?: error("Missing static method: ${names.first()}")
    return method.invoke(null, *args)
  }

  private fun invokeInt(target: Any, methodName: String): Int {
    val method = target.javaClass.methods.firstOrNull { it.name == methodName && it.parameterTypes.isEmpty() }
      ?: error("Missing method: $methodName")
    return method.invoke(target) as? Int ?: 0
  }

  private fun invokeInstance(target: Any, names: List<String>, vararg args: Any?): Any? {
    val method = target.javaClass.methods.firstOrNull { candidate ->
      candidate.name in names && candidate.parameterTypes.size == args.size
    } ?: error("Missing instance method: ${names.first()}")
    return method.invoke(target, *args)
  }

  private fun readableMapToMap(readableMap: ReadableMap): Map<String, Any?> {
    val iterator = readableMap.keySetIterator()
    val result = mutableMapOf<String, Any?>()
    while (iterator.hasNextKey()) {
      val key = iterator.nextKey()
      result[key] = when (readableMap.getType(key).name) {
        "Null" -> null
        "Boolean" -> readableMap.getBoolean(key)
        "Number" -> readableMap.getDouble(key)
        "String" -> readableMap.getString(key)
        "Map" -> readableMap.getMap(key)?.let { readableMapToMap(it) }
        else -> null
      }
    }
    return result
  }

  private fun emitStatus(status: String) {
    val payload = Arguments.createMap().apply {
      putString("status", status)
    }
    reactApplicationContext
      .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
      .emit("onTTSStatus", payload)
  }

  private fun emitError(message: String, errorCode: Int? = null) {
    val payload = Arguments.createMap().apply {
      putString("message", message)
      if (errorCode != null) {
        putInt("code", errorCode)
      }
    }
    reactApplicationContext
      .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
      .emit("onTTSError", payload)
  }
}
