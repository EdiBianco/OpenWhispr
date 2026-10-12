package com.edib.openwhispr

import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class LocalTranscriberTest {
    private val transducerModel = MODEL_CATALOG.first { it.archive == "sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8" }

    @Test
    fun `empty dictionary leaves compatible transducer in greedy mode without hotword file`() {
        withTempDir { dir ->
            val file = File(dir, "hotwords.txt").apply { writeText("stale") }
            val config = LocalTranscriber.configureHotwords(
                transducerConfig(), ModelArchitecture.NEMO_TRANSDUCER, transducerModel, emptyList(), file,
            )

            LocalTranscriber.writeHotwordsIfConfigured(config, file, emptyList())

            assertEquals("greedy_search", config.decodingMethod)
            assertEquals("", config.hotwordsFile)
            assertEquals("", config.modelConfig.modelingUnit)
            assertFalse(file.exists())
        }
    }

    @Test
    fun `compatible catalog transducer gets modified beam search and canonical plus alias hotwords`() {
        withTempDir { dir ->
            val file = File(dir, "dictionary/hotwords.txt")
            val vocabulary = listOf("OpenWhispr | open whisper, open wisper")
            val config = LocalTranscriber.configureHotwords(
                transducerConfig(), ModelArchitecture.NEMO_TRANSDUCER, transducerModel, vocabulary, file,
            )

            LocalTranscriber.writeHotwordsIfConfigured(config, file, vocabulary)

            assertEquals("modified_beam_search", config.decodingMethod)
            assertEquals(file.absolutePath, config.hotwordsFile)
            assertEquals(2.0f, config.hotwordsScore, 0.001f)
            assertEquals(4, config.maxActivePaths)
            assertEquals("nemo_transducer", config.modelConfig.modelType)
            assertEquals("cjkchar", config.modelConfig.modelingUnit)
            assertEquals("", config.modelConfig.bpeVocab)
            assertEquals("OpenWhispr\nopen whisper\nopen wisper", file.readText())
        }
    }

    @Test
    fun `incompatible catalog architectures do not receive hotword configuration`() {
        val models = listOf(
            MODEL_CATALOG.first { it.architecture == ModelArchitecture.NEMO_CTC },
            MODEL_CATALOG.first { it.architecture == ModelArchitecture.WHISPER },
            MODEL_CATALOG.first { it.architecture == ModelArchitecture.MOONSHINE },
        )
        val architectures = listOf(ModelArchitecture.NEMO_CTC, ModelArchitecture.WHISPER, ModelArchitecture.MOONSHINE)

        models.zip(architectures).forEach { (model, architecture) ->
            withTempDir { dir ->
                val file = File(dir, "hotwords.txt")
                val config = LocalTranscriber.configureHotwords(
                    OfflineRecognizerConfig(), architecture, model, listOf("NASA"), file,
                )
                LocalTranscriber.writeHotwordsIfConfigured(config, file, listOf("NASA"))

                assertEquals("greedy_search", config.decodingMethod)
                assertEquals("", config.hotwordsFile)
                assertFalse(file.exists())
            }
        }
    }

    @Test
    fun `catalog and detected architecture mismatch raises controlled configuration error`() {
        assertThrows(HotwordConfigurationException::class.java) {
            LocalTranscriber.configureHotwords(
                transducerConfig(), ModelArchitecture.NEMO_CTC, transducerModel, listOf("NASA"), File("unused"),
            )
        }
    }

    @Test
    fun `invalid configured hotword path raises instead of silently falling back`() {
        val config = transducerConfig().apply {
            decodingMethod = "modified_beam_search"
            hotwordsFile = "/different/file"
        }
        assertThrows(HotwordConfigurationException::class.java) {
            LocalTranscriber.writeHotwordsIfConfigured(config, File("expected"), listOf("NASA"))
        }
    }

    private fun transducerConfig() = OfflineRecognizerConfig(
        modelConfig = OfflineModelConfig(
            transducer = OfflineTransducerModelConfig("encoder.onnx", "decoder.onnx", "joiner.onnx"),
            tokens = "tokens.txt",
            modelType = "nemo_transducer",
        ),
    )

    private fun withTempDir(block: (File) -> Unit) {
        val dir = Files.createTempDirectory("local-transcriber-test").toFile()
        try {
            block(dir)
        } finally {
            dir.deleteRecursively()
        }
    }
}
