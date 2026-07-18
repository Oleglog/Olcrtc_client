package io.github.oleglog.olcrtc.client.profile

internal fun <T> orderProfiles(
    profiles: List<T>,
    lastSuccessfulReference: String?,
    reference: (T) -> String,
    favorite: (T) -> Boolean,
): List<T> = profiles.withIndex()
    .sortedWith(
        compareByDescending<IndexedValue<T>> { favorite(it.value) }
            .thenByDescending { reference(it.value) == lastSuccessfulReference }
            .thenBy { it.index },
    )
    .map { it.value }
