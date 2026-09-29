package dev.krispr.sample

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * SAFE_CALL_BODY: `x?.let { }` statements skipped; REMOVE_ASSIGNMENT: member and state stores skipped;
 * EMPTY_STRING_RETURNS: String returns replaced by `""`.
 */
class TagEditor(private val onSaved: (() -> Unit)?) {
    private val _saving = MutableStateFlow(false)
    val saving: StateFlow<Boolean> = _saving.asStateFlow()
    var lastSaved: Map<String, String> = emptyMap()
        private set

    fun save(title: String?, album: String?): Map<String, String> {
        _saving.value = true
        val tags = mutableMapOf<String, String>()
        title?.let { tags.put("TITLE", it.trim()) }
        // Weak: no test passes an album, so skipping this write goes unnoticed.
        album?.let { tags.put("ALBUM", it) }
        onSaved?.invoke()
        lastSaved = tags
        _saving.value = false
        return tags
    }

    // Weak: the test only checks that a caption came back, so `""` goes unnoticed.
    fun caption(): String? = lastSaved["TITLE"]?.let { "Now editing: $it" }
}
