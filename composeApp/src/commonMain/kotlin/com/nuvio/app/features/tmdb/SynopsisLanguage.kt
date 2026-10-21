package com.nuvio.app.features.tmdb

private val spanishWords = setOf(
    "el", "la", "los", "las", "de", "del", "que", "en", "un", "una", "con", "por", "para",
    "su", "sus", "es", "se", "al", "lo", "como", "más", "mas", "pero", "esta", "este",
    "está", "estan", "están", "son", "fue", "era", "hay", "ya", "entre", "sobre",
    "cuando", "también", "tambien", "porque", "desde", "hasta", "sin", "todo", "todos",
    "tiene", "han", "después", "despues", "durante", "mientras", "contra", "hacia",
    "donde", "unos", "unas", "y",
)

private val englishWords = setOf(
    "the", "and", "of", "to", "in", "is", "that", "for", "with", "his", "her", "was",
    "are", "from", "this", "have", "be", "on", "as", "by", "an", "it", "at", "he",
    "she", "they", "their", "its", "or", "not", "but", "which", "who", "into",
    "after", "about", "when", "than", "been", "were", "has", "had", "will", "would",
    "can", "them", "him", "out", "what", "there", "we", "you", "your", "if", "do",
    "just", "over", "also", "then", "how", "our", "these", "because",
    "where", "through", "before", "between", "while", "during", "without", "against",
)

private val words = Regex("\\p{L}+")

internal fun String.isSpanishSynopsis(): Boolean {
    if (isBlank()) return true
    var spanish = 0
    var english = 0
    for (match in words.findAll(this)) {
        val word = match.value.lowercase()
        when {
            word in spanishWords -> spanish++
            word in englishWords -> english++
        }
    }
    if (any { it == 'ñ' || it == 'Ñ' || it == '¿' || it == '¡' }) spanish += 2
    return spanish > english
}

/** Keeps the addon synopsis when TMDB only has the English fallback. */
internal fun chooseSynopsis(incoming: String?, current: String?): String? {
    val next = incoming?.trim()?.takeIf { it.isNotEmpty() }
    val prev = current?.trim()?.takeIf { it.isNotEmpty() }
    if (next == null) return prev
    if (prev == null) return next
    if (!next.isSpanishSynopsis()) return prev
    return next
}
