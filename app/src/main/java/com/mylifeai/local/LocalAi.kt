package com.mylifeai.local

import android.content.Context
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import java.io.File
import java.util.concurrent.Executors

/** Uses a model file explicitly selected by the user. No paid cloud AI API calls. */
class LocalAi(private val context: Context) {
    private val executor = Executors.newSingleThreadExecutor()
    private var engine: Engine? = null
    private var activePath: String? = null
    val file: File get() = File(context.filesDir, "gemma-4-E4B-it.litertlm")
    fun isModelReady() = file.exists() && file.length() > 1_000_000_000L

    fun reset() {
        executor.execute {
            runCatching { engine?.close() }
            engine = null
            activePath = null
        }
    }

    fun ask(prompt: String, image: ByteArray? = null, done: (Result<String>) -> Unit) {
        executor.execute {
            val result = runCatching {
                check(isModelReady()) { "Gemma 4 E4B 모델 파일을 먼저 연결하세요." }
                if (engine == null || activePath != file.absolutePath) {
                    runCatching { engine?.close() }
                    val config = EngineConfig(
                        modelPath = file.absolutePath,
                        backend = Backend.CPU(),
                        visionBackend = Backend.CPU(),
                        cacheDir = context.cacheDir.absolutePath
                    )
                    val next = Engine(config)
                    next.initialize()
                    engine = next
                    activePath = file.absolutePath
                }
                val config = ConversationConfig(
                    systemInstruction = Contents.of("한국어 생활비서입니다. 정보가 불확실하면 추정이라 말하고, 수치나 날짜를 만들어내지 마세요. 기록이나 일정의 저장은 반드시 사용자 승인을 거칩니다.")
                )
                checkNotNull(engine).createConversation(config).use { conversation ->
                    if (image == null) conversation.sendMessage(prompt).toString()
                    else conversation.sendMessage(Contents.of(Content.ImageBytes(image), Content.Text(prompt))).toString()
                }
            }
            done(result)
        }
    }

    fun close() {
        executor.execute {
            runCatching { engine?.close() }
            engine = null
            activePath = null
        }
        executor.shutdown()
    }
}
