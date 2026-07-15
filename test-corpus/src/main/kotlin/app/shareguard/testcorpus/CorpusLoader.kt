package app.shareguard.testcorpus

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

object CorpusLoader {
    const val DEFAULT_RESOURCE: String = "corpus/adversarial-corpus-v1.json"

    private val json = Json {
        ignoreUnknownKeys = false
        isLenient = false
        explicitNulls = true
    }

    fun loadDefault(
        classLoader: ClassLoader = CorpusLoader::class.java.classLoader,
    ): AdversarialCorpus = loadResource(DEFAULT_RESOURCE, classLoader)

    fun loadResource(
        resourcePath: String,
        classLoader: ClassLoader = CorpusLoader::class.java.classLoader,
    ): AdversarialCorpus {
        val normalizedPath = resourcePath.removePrefix("/")
        val payload = classLoader.getResourceAsStream(normalizedPath)?.bufferedReader()?.use { it.readText() }
            ?: throw CorpusLoadException("Corpus resource not found: $normalizedPath")
        return decode(payload, normalizedPath)
    }

    fun decode(payload: String, sourceName: String = "<memory>"): AdversarialCorpus =
        try {
            json.decodeFromString<AdversarialCorpus>(payload)
        } catch (error: SerializationException) {
            throw CorpusLoadException("Invalid corpus fixture: $sourceName", error)
        } catch (error: IllegalArgumentException) {
            throw CorpusLoadException("Invalid corpus fixture: $sourceName", error)
        }
}

class CorpusLoadException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)
