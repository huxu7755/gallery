/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.ai.edge.gallery.api

import android.content.Context
import android.util.Log
import com.google.ai.edge.gallery.data.ConfigKeys
import com.google.ai.edge.gallery.data.DataStoreRepository
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.runtime.ResultListener
import com.google.ai.edge.gallery.runtime.runtimeHelper
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationEngine
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.response.respondTextWriter
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.CountDownLatch

private const val TAG = "LocalApiServer"
private const val PORT = 8080
private const val HOST = "127.0.0.1"

/**
 * Local HTTP API server that wraps Gallery's LLM inference engine.
 *
 * Security: binds only to 127.0.0.1 — no network exposure.
 *
 * Endpoints:
 *   GET  /health             → health check
 *   GET  /api/models         → list available models
 *   GET  /api/status         → server status
 *   POST /api/chat           → chat completion (streaming or non-streaming)
 *   POST /api/chat/reset     → reset conversation for a model
 *   POST /api/shutdown       → stop the server
 */
object LocalApiServer {

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    prettyPrint = false
  }

  private var appContext: Context? = null
  private var dataStoreRepository: DataStoreRepository? = null

  // ── Model registry ────────────────────────────────────────────────────
  // Models are registered by the app when they become available (downloaded/init'd).
  // This avoids depending on ViewModel internals.
  private val modelRegistry = mutableMapOf<String, Model>()

  /** Register a model so it's discoverable via /api/models and usable via /api/chat.
   *  Call from ModelManagerViewModel or wherever models are loaded. */
  fun registerModel(model: Model) {
    modelRegistry[model.name] = model
    Log.d(TAG, "Registered model: ${model.name}")
  }

  /** Unregister a model (e.g. after cleanup). */
  fun unregisterModel(name: String) {
    modelRegistry.remove(name)
    Log.d(TAG, "Unregistered model: $name")
  }

  /** Register all models from a list of Tasks. Convenience for bulk registration. */
  fun registerModelsFromTasks(tasks: List<com.google.ai.edge.gallery.data.Task>) {
    for (task in tasks) {
      for (model in task.models) {
        registerModel(model)
      }
    }
  }

  @Volatile private var server: NettyApplicationEngine? = null
  @Volatile private var _running = false

  val running: Boolean get() = _running

  /** Inject app dependencies — call from GalleryApplication.onCreate() */
  fun initialize(context: Context, repository: DataStoreRepository) {
    appContext = context.applicationContext
    dataStoreRepository = repository
    Log.i(TAG, "LocalApiServer initialized")
  }

  /** Start the server on 127.0.0.1:8080 */
  fun start() {
    if (server != null) {
      Log.w(TAG, "Server already running")
      return
    }
    try {
      server = embeddedServer(
        Netty,
        port = PORT,
        host = HOST,
      ) {
        configureApp()
        configureRouting()
      }
      server?.start(wait = false)
      _running = true
      Log.i(TAG, "Local API server started at http://$HOST:$PORT")
    } catch (e: Exception) {
      Log.e(TAG, "Failed to start server", e)
    }
  }

  /** Stop the server */
  fun stop() {
    try {
      server?.stop(1000, 5000)
      server = null
    } catch (e: Exception) {
      Log.e(TAG, "Error stopping server", e)
    }
    _running = false
    Log.i(TAG, "Local API server stopped")
  }

  private fun Application.configureApp() {
    install(CORS) {
      allowHost(host = "localhost")
      allowHost(host = "127.0.0.1")
      allowMethod(io.ktor.http.HttpMethod.Options)
      allowMethod(io.ktor.http.HttpMethod.Get)
      allowMethod(io.ktor.http.HttpMethod.Post)
      allowHeader(HttpHeaders.ContentType)
      allowHeader(HttpHeaders.Accept)
      allowHeader(HttpHeaders.CacheControl)
    }
    install(ContentNegotiation) {
      json(json)
    }
  }

  private fun Application.configureRouting() {
    routing {
      get("/health") {
        call.respondText(
          text = """{"status":"ok","server":"LocalApiServer","version":"1.0.0"}""",
          contentType = ContentType.Application.Json
        )
      }

      get("/api/status") {
        call.respondJson(buildJsonObject {
          put("running", JsonPrimitive(_running))
          put("host", JsonPrimitive(HOST))
          put("port", JsonPrimitive(PORT))
        })
      }

      get("/api/models") {
        try {
          val models = listLoadedModels()
          call.respondJson(buildJsonObject {
            put("models", JsonArray(models.map { it.toJson() }))
            put("default", JsonPrimitive(models.firstOrNull()?.name ?: ""))
          })
        } catch (e: Exception) {
          Log.e(TAG, "Failed to list models", e)
          call.respondError("Failed to list models: ${e.message}", "MODEL_LIST_ERROR")
        }
      }

      post("/api/chat") {
        try {
          val body = call.receiveText()
          val request = json.decodeFromString<ChatRequest>(body)
          if (request.stream) {
            handleStreamingChat(call, request)
          } else {
            handleBlockingChat(call, request)
          }
        } catch (e: Exception) {
          Log.e(TAG, "Chat error", e)
          call.respondError("Chat error: ${e.message}", "CHAT_ERROR")
        }
      }

      post("/api/chat/reset") {
        try {
          val body = call.receiveText()
          val elem = json.parseToJsonElement(body).jsonObject
          val modelName = elem["model"]?.jsonPrimitive?.content ?: ""
          if (modelName.isNotEmpty()) {
            resetConversation(modelName)
          }
          call.respondJson(buildJsonObject {
            put("ok", JsonPrimitive(true))
            put("model", JsonPrimitive(modelName))
          })
        } catch (e: Exception) {
          call.respondError("Reset error: ${e.message}", "RESET_ERROR")
        }
      }

      post("/api/shutdown") {
        call.respondJson(buildJsonObject {
          put("ok", JsonPrimitive(true))
          put("message", JsonPrimitive("Server shutting down"))
        })
        scope.launch { stop() }
      }
    }
  }

  // ── Chat handlers ──────────────────────────────────────────────────────────

  private suspend fun handleBlockingChat(call: ApplicationCall, request: ChatRequest) {
    val context = appContext ?: run {
      call.respondError("Server not initialized", "NOT_INITIALIZED", HttpStatusCode.InternalServerError)
      return
    }
    val model = findModelByName(request.model) ?: run {
      call.respondError("Model not found: ${request.model}", "MODEL_NOT_FOUND", HttpStatusCode.NotFound)
      return
    }
    val lastUserMessage = request.messages.lastOrNull { it.role == "user" }?.content ?: ""
    if (lastUserMessage.isEmpty()) {
      call.respondError("No user message found", "INVALID_REQUEST", HttpStatusCode.BadRequest)
      return
    }

    ensureModelInitialized(context, model)

    val fullResponse = StringBuilder()
    val promptTokens = estimateTokens(buildPromptFromMessages(request.messages.dropLast(1)))
    val doneLatch = CountDownLatch(1)
    var completionTokens = 0

    val resultListener: ResultListener = { partialResult, done, _ ->
      if (partialResult.isNotEmpty()) fullResponse.append(partialResult)
      if (done) {
        completionTokens = estimateTokens(fullResponse.toString())
        doneLatch.countDown()
      }
    }

    runInference(model, lastUserMessage, resultListener, {}, request)

    // Wait for inference to complete (max 120 seconds)
    doneLatch.await(120, java.util.concurrent.TimeUnit.SECONDS)

    val response = ChatResponse(
      model = request.model,
      message = ChatResponse.Message(content = fullResponse.toString()),
      done = true,
      usage = ChatResponse.Usage(
        promptTokens = promptTokens,
        completionTokens = completionTokens,
        totalTokens = promptTokens + completionTokens,
      )
    )
    call.respondText(
      text = json.encodeToString(ChatResponse.serializer(), response),
      contentType = ContentType.Application.Json
    )
  }

  private suspend fun handleStreamingChat(call: ApplicationCall, request: ChatRequest) {
    val context = appContext ?: run {
      call.respondError("Server not initialized", "NOT_INITIALIZED", HttpStatusCode.InternalServerError)
      return
    }
    val model = findModelByName(request.model) ?: run {
      call.respondError("Model not found: ${request.model}", "MODEL_NOT_FOUND", HttpStatusCode.NotFound)
      return
    }
    val lastUserMessage = request.messages.lastOrNull { it.role == "user" }?.content ?: ""
    if (lastUserMessage.isEmpty()) {
      call.respondError("No user message found", "INVALID_REQUEST", HttpStatusCode.BadRequest)
      return
    }

    ensureModelInitialized(context, model)

    val startTokens = estimateTokens(buildPromptFromMessages(request.messages.dropLast(1)))
    var completionTokens = 0

    call.respondTextWriter(contentType = ContentType.Text.EventStream) {
      val doneLatch = CountDownLatch(1)

      val resultListener: ResultListener = { partialResult, done, _ ->
        val chunk = StreamChunk(
          model = request.model,
          chunk = partialResult,
          done = done,
          usage = if (done) {
            completionTokens = estimateTokens(partialResult)
            ChatResponse.Usage(
              promptTokens = startTokens,
              completionTokens = completionTokens,
              totalTokens = startTokens + completionTokens,
            )
          } else null,
        )
        val line = "data: ${json.encodeToString(StreamChunk.serializer(), chunk)}\n\n"
        try {
          write(line)
          flush()
        } catch (e: Exception) {
          Log.w(TAG, "SSE write failed (client disconnected?)")
        }
        if (done) doneLatch.countDown()
      }

      runInference(model, lastUserMessage, resultListener, {}, request)

      // Wait for all tokens to be streamed
      doneLatch.await(120, java.util.concurrent.TimeUnit.SECONDS)

      // Send [DONE] sentinel
      try {
        write("data: [DONE]\n\n")
        flush()
      } catch (_: Exception) { /* ignore */ }
    }
  }

  // ── Inference ──────────────────────────────────────────────────────────────

  private fun runInference(
    model: Model,
    input: String,
    resultListener: ResultListener,
    cleanUpListener: com.google.ai.edge.gallery.runtime.CleanUpListener,
    request: ChatRequest,
  ) {
    // Apply parameter overrides from request
    val original = model.configValues.toMutableMap()
    request.temperature?.let { original[ConfigKeys.TEMPERATURE.label] = it.toFloat() }
    request.topK?.let { original[ConfigKeys.TOPK.label] = it }
    request.maxTokens?.let { original[ConfigKeys.MAX_TOKENS.label] = it }
    model.configValues = original

    model.runtimeHelper.runInference(
      model = model,
      input = input,
      resultListener = resultListener,
      cleanUpListener = cleanUpListener,
      onError = { error -> Log.e(TAG, "Inference error: $error") },
      images = emptyList(),
      audioClips = emptyList(),
      coroutineScope = scope,
      extraContext = null,
    )
  }

  // ── Model helpers ──────────────────────────────────────────────────────────

  private fun findModelByName(name: String): Model? = modelRegistry[name]

  private fun listLoadedModels(): List<ModelListResponse.ModelInfo> =
    modelRegistry.values.map { model ->
      ModelListResponse.ModelInfo(
        name = model.name,
        displayName = model.displayName.ifEmpty { model.name },
        downloaded = model.instance != null,
        sizeInBytes = model.sizeInBytes,
        runtimeType = model.runtimeType.name,
        capabilities = buildList {
          if (model.llmSupportImage) add("image")
          if (model.llmSupportAudio) add("audio")
        }
      )
    }

  private fun ensureModelInitialized(context: Context, model: Model) {
    if (model.instance != null) return
    val helper = model.runtimeHelper
    val latch = CountDownLatch(1)
    var initResult = ""
    helper.initialize(
      context = context,
      model = model,
      supportImage = model.llmSupportImage,
      supportAudio = model.llmSupportAudio,
      onDone = { result ->
        initResult = result
        latch.countDown()
      },
      coroutineScope = scope,
    )
    val completed = latch.await(60, java.util.concurrent.TimeUnit.SECONDS)
    if (!completed) throw IllegalStateException("Model init timed out after 60s")
    if (initResult.isNotEmpty() && initResult.contains("failed", ignoreCase = true)) {
      throw IllegalStateException("Model init failed: $initResult")
    }
  }

  private fun resetConversation(modelName: String) {
    val model = findModelByName(modelName) ?: return
    val context = appContext ?: return
    model.runtimeHelper.resetConversation(
      model = model,
      supportImage = model.llmSupportImage,
      supportAudio = model.llmSupportAudio,
    )
  }

  // ── Utilities ─────────────────────────────────────────────────────────────

  private fun buildPromptFromMessages(messages: List<ChatRequest.Message>): String =
    buildString {
      for (msg in messages) append("${msg.role}: ${msg.content}\n")
    }

  private fun estimateTokens(text: String): Int =
    (text.codePointCount(0, text.length) * 0.75).toInt().coerceAtLeast(1)

  private suspend fun ApplicationCall.respondError(
    message: String,
    code: String,
    status: HttpStatusCode = HttpStatusCode.BadRequest,
  ) {
    respondText(
      text = json.encodeToString(ErrorResponse.serializer(), ErrorResponse(error = message, code = code)),
      status = status,
      contentType = ContentType.Application.Json,
    )
  }

  private suspend fun ApplicationCall.respondJson(obj: JsonObject) {
    respondText(obj.toString(), ContentType.Application.Json)
  }
}

/** Extension: ModelListResponse.ModelInfo → JsonObject */
private fun ModelListResponse.ModelInfo.toJson(): JsonObject = buildJsonObject {
  put("name", JsonPrimitive(name))
  put("displayName", JsonPrimitive(displayName))
  put("downloaded", JsonPrimitive(downloaded))
  put("sizeInBytes", JsonPrimitive(sizeInBytes))
  put("runtimeType", JsonPrimitive(runtimeType))
  put("capabilities", JsonArray(capabilities.map { JsonPrimitive(it) }))
}
