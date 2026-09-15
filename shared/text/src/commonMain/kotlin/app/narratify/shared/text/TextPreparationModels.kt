package app.narratify.shared.text

import com.narratify.domain.PublicationLocator
import com.narratify.domain.SemanticRole
import com.narratify.domain.SpokenToken

data class TextPreparationOptions(
    val defaultLanguage: String = "en-US",
    val footnotePolicy: FootnotePolicy = FootnotePolicy.SKIP,
    val minimumMergeCharacters: Int = 45,
    val targetChunkCharacters: Int = 220,
    val maximumChunkCharacters: Int = 350,
) {
    init {
        require(defaultLanguage.isNotBlank())
        require(minimumMergeCharacters >= 0)
        require(targetChunkCharacters >= minimumMergeCharacters)
        require(maximumChunkCharacters >= targetChunkCharacters)
    }
}

enum class FootnotePolicy { SKIP, INCLUDE_INLINE }

data class PreparedTtsChunk(
    val id: String,
    val tokens: List<SpokenToken>,
    val speechText: String,
    val language: String,
    val semanticRole: SemanticRole,
    val sourceStart: PublicationLocator,
    val sourceEnd: PublicationLocator,
) {
    init {
        require(id.isNotBlank())
        require(tokens.isNotEmpty())
        require(tokens.map(SpokenToken::index) == tokens.indices.toList())
        require(speechText.isNotBlank())
        require(language.isNotBlank())
        require(sourceStart.publicationId == sourceEnd.publicationId)
    }
}
