package com.edib.openwhispr

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.*
import java.io.File

class HotwordConfigurationException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Local on-device transcription via sherpa-onnx. */
class LocalTranscriber private constructor(private val recognizer: OfflineRecognizer) {

    fun transcribe(samples: FloatArray, sampleRate: Int = 16000): String {
        val stream = recognizer.createStream()
        return try {
            stream.acceptWaveform(samples, sampleRate)
            recognizer.decode(stream)
            recognizer.getResult(stream).text.trim()
        } finally {
            stream.release()
        }
    }

    companion object {
        private const val TAG = "LocalTranscriber"
        private const val HOTWORDS_SCORE = 2.0f
        private const val MAX_ACTIVE_PATHS = 4
        private const val HOTWORD_MODEL_ARCHIVE = "sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8"

        fun availableModels(ctx: Context): List<String> {
            val modelsDir = File(ctx.filesDir, "models")
            if (!modelsDir.exists()) return emptyList()
            return modelsDir.listFiles()?.filter { it.isDirectory }?.map { it.name } ?: emptyList()
        }

        fun create(ctx: Context, modelName: String): LocalTranscriber? {
            val modelDir = File(ctx.filesDir, "models/$modelName")
            if (!modelDir.exists()) {
                Log.e(TAG, "Model directory is missing: $modelName")
                return null
            }

            val vocabulary = Dictionary.load(ctx.getSharedPreferences("openwhispr", Context.MODE_PRIVATE))
            val hotwordsFile = File(ctx.filesDir, "dictionary/hotwords.txt")
            val config = try {
                buildRecognizerConfig(modelDir, vocabulary, hotwordsFile)
            } catch (e: HotwordConfigurationException) {
                hotwordsFile.delete()
                Log.e(TAG, "Hotword configuration failed: model=$modelName, cause=${e.message}")
                throw e
            } ?: run {
                hotwordsFile.delete()
                Log.e(TAG, "Could not identify a supported model architecture: model=$modelName")
                return null
            }

            try {
                writeHotwordsIfConfigured(config, hotwordsFile, vocabulary)
            } catch (e: Exception) {
                hotwordsFile.delete()
                val failure = HotwordConfigurationException("Could not write the hotword file", e)
                Log.e(TAG, "Hotword configuration failed: model=$modelName, cause=${e.javaClass.simpleName}")
                throw failure
            }

            return try {
                val recognizer = OfflineRecognizer(assetManager = null, config = config)
                Log.i(TAG, "Loaded model: $modelName")
                LocalTranscriber(recognizer)
            } catch (e: Exception) {
                hotwordsFile.delete()
                if (config.hotwordsFile.isNotEmpty()) {
                    val failure = HotwordConfigurationException(
                        "Recognizer rejected hotword configuration (${e.javaClass.simpleName})",
                    )
                    Log.e(TAG, "Hotword recognizer initialization failed: model=$modelName, cause=${e.javaClass.simpleName}")
                    throw failure
                }
                Log.e(TAG, "Failed to load model: $modelName, cause=${e.javaClass.simpleName}")
                null
            } catch (e: LinkageError) {
                hotwordsFile.delete()
                Log.e(TAG, "Native runtime unavailable for model=$modelName, cause=${e.javaClass.simpleName}")
                null
            }
        }

        internal fun buildRecognizerConfig(
            dir: File,
            vocabulary: List<String>,
            hotwordsFile: File,
        ): OfflineRecognizerConfig? {
            val detected = detectModelConfig(dir) ?: return null
            val model = MODEL_CATALOG.firstOrNull { it.archive == dir.name }
            return configureHotwords(detected.config, detected.architecture, model, vocabulary, hotwordsFile)
        }

        internal fun configureHotwords(
            config: OfflineRecognizerConfig,
            detectedArchitecture: ModelArchitecture,
            model: Model?,
            vocabulary: List<String>,
            hotwordsFile: File,
        ): OfflineRecognizerConfig {
            val terms = Dictionary.recognitionTerms(vocabulary)
            if (terms.isEmpty()) return withoutHotwords(config)
            val eligibleModel = model?.takeIf { it.archive == HOTWORD_MODEL_ARCHIVE } ?: return withoutHotwords(config)
            if (eligibleModel.hotwordModelingUnit == null) {
                throw HotwordConfigurationException("Catalog hotword modeling unit is missing")
            }
            if (eligibleModel.architecture != ModelArchitecture.NEMO_TRANSDUCER ||
                detectedArchitecture != ModelArchitecture.NEMO_TRANSDUCER ||
                config.modelConfig.modelType != "nemo_transducer"
            ) {
                throw HotwordConfigurationException("Catalog architecture does not match the detected transducer")
            }
            if (eligibleModel.hotwordModelingUnit != "cjkchar") {
                throw HotwordConfigurationException("Unsupported hotword modeling unit")
            }
            val transducer = config.modelConfig.transducer
            if (transducer.encoder.isBlank() || transducer.decoder.isBlank() || transducer.joiner.isBlank() ||
                config.modelConfig.tokens.isBlank()
            ) {
                throw HotwordConfigurationException("Transducer model files are incomplete")
            }
            if (config.modelConfig.bpeVocab.isNotEmpty()) {
                throw HotwordConfigurationException("This model must not configure a BPE vocabulary")
            }

            config.decodingMethod = "modified_beam_search"
            config.maxActivePaths = MAX_ACTIVE_PATHS
            config.hotwordsScore = HOTWORDS_SCORE
            config.hotwordsFile = hotwordsFile.absolutePath
            config.modelConfig.modelingUnit = "cjkchar"
            config.modelConfig.bpeVocab = ""
            return config
        }

        internal fun writeHotwordsIfConfigured(
            config: OfflineRecognizerConfig,
            hotwordsFile: File,
            vocabulary: List<String>,
        ) {
            if (config.hotwordsFile.isEmpty()) {
                hotwordsFile.delete()
                return
            }
            if (config.hotwordsFile != hotwordsFile.absolutePath ||
                Dictionary.recognitionTerms(vocabulary).isEmpty()
            ) {
                throw HotwordConfigurationException("Hotword path or dictionary is invalid")
            }
            Dictionary.writeHotwords(hotwordsFile, vocabulary)
        }

        private fun withoutHotwords(config: OfflineRecognizerConfig): OfflineRecognizerConfig {
            config.decodingMethod = "greedy_search"
            config.hotwordsFile = ""
            config.hotwordsScore = 1.5f
            config.maxActivePaths = 4
            config.modelConfig.modelingUnit = ""
            config.modelConfig.bpeVocab = ""
            return config
        }

        private data class DetectedConfig(
            val config: OfflineRecognizerConfig,
            val architecture: ModelArchitecture,
        )

        private fun detectModelConfig(dir: File): DetectedConfig? {
            val path = dir.absolutePath
            val tokens = "$path/tokens.txt"
            if (!File(tokens).exists()) return null

            if (File("$path/preprocess.onnx").exists()) {
                return DetectedConfig(
                    OfflineRecognizerConfig(
                        modelConfig = OfflineModelConfig(
                            moonshine = OfflineMoonshineModelConfig(
                                preprocessor = "$path/preprocess.onnx",
                                encoder = findFile(path, "encode") ?: return null,
                                uncachedDecoder = findFile(path, "uncached_decode") ?: return null,
                                cachedDecoder = findFile(path, "cached_decode") ?: return null,
                            ),
                            tokens = tokens,
                            numThreads = 2,
                        ),
                    ),
                    ModelArchitecture.MOONSHINE,
                )
            }

            val encoder = findFile(path, "encoder")
            val decoder = findFile(path, "decoder")
            val joiner = findFile(path, "joiner")
            if (encoder != null && decoder != null && joiner != null) {
                return DetectedConfig(
                    OfflineRecognizerConfig(
                        modelConfig = OfflineModelConfig(
                            transducer = OfflineTransducerModelConfig(encoder, decoder, joiner),
                            tokens = tokens,
                            numThreads = 2,
                            modelType = "nemo_transducer",
                        ),
                    ),
                    ModelArchitecture.NEMO_TRANSDUCER,
                )
            }

            if (encoder != null && decoder != null) {
                return DetectedConfig(
                    OfflineRecognizerConfig(
                        modelConfig = OfflineModelConfig(
                            whisper = OfflineWhisperModelConfig(encoder = encoder, decoder = decoder),
                            tokens = tokens,
                            numThreads = 2,
                            modelType = "whisper",
                        ),
                    ),
                    ModelArchitecture.WHISPER,
                )
            }

            val ctcModel = findFile(path, "model") ?: return null
            return DetectedConfig(
                OfflineRecognizerConfig(
                    modelConfig = OfflineModelConfig(
                        nemo = OfflineNemoEncDecCtcModelConfig(model = ctcModel),
                        tokens = tokens,
                        numThreads = 2,
                    ),
                ),
                ModelArchitecture.NEMO_CTC,
            )
        }

        private fun findFile(dir: String, prefix: String): String? {
            val directory = File(dir)
            directory.listFiles()?.firstOrNull { it.name.startsWith(prefix) && it.name.contains("int8") }
                ?.let { return it.absolutePath }
            return directory.listFiles()?.firstOrNull {
                it.name.startsWith(prefix) && (it.name.endsWith(".onnx") || it.name.endsWith(".ort"))
            }?.absolutePath
        }
    }
}
