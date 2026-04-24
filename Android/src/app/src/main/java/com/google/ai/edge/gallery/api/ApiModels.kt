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

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Request body for /api/chat endpoint.
 *
 * POST /api/chat
 * {
 *   "model": "gemma-3-4b-it",
 *   "messages": [
 *     {"role": "user", "content": "Hello!"}
 *   ],
 *   "stream": true,
 *   "temperature": 0.7,
 *   "max_tokens": 1024
 * }
 */
@Serializable
data class ChatRequest(
  val model: String,
  val messages: List<Message>,
  val stream: Boolean = false,
  val temperature: Double? = null,
  val maxTokens: Int? = null,
  val topP: Double? = null,
  val topK: Int? = null,
) {
  @Serializable
  data class Message(
    val role: String,
    val content: String,
  )
}

/**
 * Response body for /api/chat (non-streaming).
 *
 * POST /api/chat → 200 OK
 * {
 *   "model": "gemma-3-4b-it",
 *   "message": { "role": "assistant", "content": "Hello! How can I help?" },
 *   "done": true,
 *   "usage": { "prompt_tokens": 10, "completion_tokens": 20, "total_tokens": 30 }
 * }
 */
@Serializable
data class ChatResponse(
  val model: String,
  val message: ChatResponse.Message,
  val done: Boolean = true,
  val usage: Usage? = null,
) {
  @Serializable
  data class Message(
    val role: String = "assistant",
    val content: String,
  )

  @Serializable
  data class Usage(
    @SerialName("prompt_tokens") val promptTokens: Int = 0,
    @SerialName("completion_tokens") val completionTokens: Int = 0,
    @SerialName("total_tokens") val totalTokens: Int = 0,
  )
}

/**
 * SSE chunk for streaming /api/chat.
 *
 * Each chunk emitted over HTTP as:
 *   data: {"model":"...","chunk":"Hel","done":false}
 *   data: {"model":"...","chunk":"lo!","done":false}
 *   data: {"model":"...","chunk":"","done":true,"usage":{...}}
 *   data: [DONE]
 */
@Serializable
data class StreamChunk(
  val model: String,
  val chunk: String = "",
  val done: Boolean = false,
  val usage: ChatResponse.Usage? = null,
  val error: String? = null,
)

/**
 * Request for /api/model/list
 */
@Serializable
data class ModelListResponse(
  val models: List<ModelInfo>,
  val defaultModel: String? = null,
) {
  @Serializable
  data class ModelInfo(
    val name: String,
    val displayName: String,
    val downloaded: Boolean,
    val sizeInBytes: Long,
    val runtimeType: String,
    val capabilities: List<String> = emptyList(),
  )
}

/**
 * Generic error response.
 */
@Serializable
data class ErrorResponse(
  val error: String,
  val code: String,
)
