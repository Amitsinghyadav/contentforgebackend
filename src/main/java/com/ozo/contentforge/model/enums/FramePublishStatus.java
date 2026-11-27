package com.ozo.contentforge.model.enums;

public enum FramePublishStatus {
    /** waiting for the story's publish time */
    PENDING,
    PUBLISHED,
    /** needs a sticker: the team posts it by hand */
    MANUAL_PENDING,
    MANUAL_DONE,
    FAILED
}
