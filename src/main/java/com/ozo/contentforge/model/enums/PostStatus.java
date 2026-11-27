package com.ozo.contentforge.model.enums;

public enum PostStatus {
    QUEUED,
    PLANNING,
    PENDING_TEXT_APPROVAL,
    TEXT_APPROVED,
    GENERATING_AUDIO_SAMPLES,
    PENDING_AUDIO_APPROVAL,
    AUDIO_APPROVED,
    MEDIA_GENERATING,
    PENDING_FINAL_APPROVAL,
    FINAL_APPROVED,
    SCHEDULED,
    PUBLISHING,
    PUBLISHED,
    FAILED,
    CANCELLED,
    /** publishing failed after all retries; the error is in publishError */
    PUBLISH_FAILED,
    /** a story whose frames all need stickers: the team posts them by hand */
    AWAITING_MANUAL_POST
}
