package com.eka.scribesdk.api.models

/**
 * Whether a note has been sent to the EMR, read from the document's `publish.emr_webhook`.
 *
 * Editing a published note marks it [PublishState.NOT_PUBLISHED] again (as on web), so the
 * doctor republishes the edited version.
 */
data class PublishStatus(
    val state: PublishState,
    /** When the publish state last changed (epoch millis); the "Last done" time. Null if unknown. */
    val updatedAtMs: Long?,
    /** `emr_webhook.status` exactly as the server sent it, for values this SDK does not map. */
    val rawStatus: String?
)

enum class PublishState {
    /** Never published, or unpublished by an edit. */
    NOT_PUBLISHED,

    /** Publish was accepted; the EMR has not confirmed yet. */
    IN_PROGRESS,

    /** The EMR has the current version. */
    PUBLISHED,

    /** The EMR rejected or failed to take the note. */
    FAILED
}
