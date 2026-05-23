package com.autodirect.engine.bridge

import android.content.Context
import com.unity3d.player.UnityPlayer
import org.json.JSONArray
import org.json.JSONObject

enum class ModelFormat {
    GLTF,
    GLB
}

data class ModelLoadRequest(
    val modelUri: String,
    val format: ModelFormat,
    val position: FloatArray = floatArrayOf(0f, 0f, 0f),
    val rotation: FloatArray = floatArrayOf(0f, 0f, 0f, 1f),
    val scale: FloatArray = floatArrayOf(1f, 1f, 1f),
    val metadata: Map<String, String> = emptyMap()
)

data class SceneCommand(
    val command: String,
    val params: JSONObject = JSONObject()
)

class UnityRenderBridge private constructor(
    private val context: Context
) {
    private var unityPlayer: UnityPlayer? = null
    private val commandQueue = mutableListOf<SceneCommand>()
    private var isReady = false

    companion object {
        @Volatile
        private var instance: UnityRenderBridge? = null

        private val UNITY_GAME_OBJECT = "AutoDirectBridge"
        private val UNITY_METHOD_LOAD = "OnModelLoadCommand"
        private val UNITY_METHOD_SCENE = "OnSceneCommand"
        private val UNITY_METHOD_ANIMATION = "OnAnimationCommand"

        fun initialize(context: Context): UnityRenderBridge {
            return instance ?: synchronized(this) {
                instance ?: UnityRenderBridge(context).also { instance = it }
            }
        }

        fun getInstance(): UnityRenderBridge {
            return instance ?: throw IllegalStateException(
                "UnityRenderBridge not initialized. Call initialize(context) first."
            )
        }

        fun destroy() {
            synchronized(this) {
                instance?.unityPlayer = null
                instance = null
            }
        }
    }

    fun attachUnityPlayer(player: UnityPlayer) {
        unityPlayer = player
        isReady = true
        flushCommandQueue()
    }

    fun detachUnityPlayer() {
        unityPlayer = null
        isReady = false
    }

    fun loadModel(request: ModelLoadRequest) {
        val json = JSONObject().apply {
            put("uri", request.modelUri)
            put("format", request.format.name.lowercase())
            put("position", floatArrayToJson(request.position))
            put("rotation", floatArrayToJson(request.rotation))
            put("scale", floatArrayToJson(request.scale))
            put("metadata", mapToJson(request.metadata))
        }

        sendToUnity(UNITY_METHOD_LOAD, json.toString())
    }

    fun loadModelBatch(requests: List<ModelLoadRequest>) {
        val jsonArray = JSONArray()
        requests.forEach { request ->
            val json = JSONObject().apply {
                put("uri", request.modelUri)
                put("format", request.format.name.lowercase())
                put("position", floatArrayToJson(request.position))
                put("rotation", floatArrayToJson(request.rotation))
                put("scale", floatArrayToJson(request.scale))
                put("metadata", mapToJson(request.metadata))
            }
            jsonArray.put(json)
        }

        val envelope = JSONObject().apply {
            put("batch", true)
            put("models", jsonArray)
        }

        sendToUnity(UNITY_METHOD_LOAD, envelope.toString())
    }

    fun unloadModel(modelId: String) {
        val json = JSONObject().apply {
            put("action", "unload")
            put("modelId", modelId)
        }
        sendToUnity(UNITY_METHOD_SCENE, json.toString())
    }

    fun playAnimation(modelId: String, clipName: String, loop: Boolean = false) {
        val json = JSONObject().apply {
            put("modelId", modelId)
            put("clip", clipName)
            put("loop", loop)
        }
        sendToUnity(UNITY_METHOD_ANIMATION, json.toString())
    }

    fun setCameraTransform(
        position: FloatArray,
        lookAt: FloatArray,
        fov: Float = 60f
    ) {
        val json = JSONObject().apply {
            put("action", "setCamera")
            put("position", floatArrayToJson(position))
            put("lookAt", floatArrayToJson(lookAt))
            put("fov", fov)
        }
        sendToUnity(UNITY_METHOD_SCENE, json.toString())
    }

    fun setLighting(
        ambientColor: FloatArray,
        ambientIntensity: Float,
        directionalDir: FloatArray? = null,
        directionalColor: FloatArray? = null
    ) {
        val json = JSONObject().apply {
            put("action", "setLighting")
            put("ambientColor", floatArrayToJson(ambientColor))
            put("ambientIntensity", ambientIntensity)
            directionalDir?.let { put("directionalDir", floatArrayToJson(it)) }
            directionalColor?.let { put("directionalColor", floatArrayToJson(it)) }
        }
        sendToUnity(UNITY_METHOD_SCENE, json.toString())
    }

    private fun sendToUnity(methodName: String, message: String) {
        if (isReady && unityPlayer != null) {
            try {
                UnityPlayer.UnitySendMessage(UNITY_GAME_OBJECT, methodName, message)
            } catch (e: Exception) {
                enqueueCommand(SceneCommand(methodName, JSONObject(message)))
            }
        } else {
            enqueueCommand(SceneCommand(methodName, JSONObject(message)))
        }
    }

    private fun enqueueCommand(command: SceneCommand) {
        synchronized(commandQueue) {
            commandQueue.add(command)
        }
    }

    private fun flushCommandQueue() {
        synchronized(commandQueue) {
            commandQueue.forEach { cmd ->
                try {
                    UnityPlayer.UnitySendMessage(
                        UNITY_GAME_OBJECT,
                        cmd.command,
                        cmd.params.toString()
                    )
                } catch (_: Exception) { }
            }
            commandQueue.clear()
        }
    }

    private fun floatArrayToJson(arr: FloatArray): JSONArray {
        return JSONArray().apply {
            arr.forEach { put(it) }
        }
    }

    private fun mapToJson(map: Map<String, String>): JSONObject {
        return JSONObject().apply {
            map.forEach { (k, v) -> put(k, v) }
        }
    }
}
