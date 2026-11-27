package com.ozo.contentforge.model.enums;

/**
 * What a story frame is. Instagram's publishing API can't add stickers, so frames that need one (poll, quiz,
 * question box, link) are made by the system but posted by hand by the team.
 */
public enum StoryFrameType {
    TIP,
    QUOTE,
    BEHIND_THE_SCENES,
    SHARE_POST,
    ANNOUNCEMENT,
    OFFER,
    POLL,
    QUIZ,
    QUESTION,
    LINK;

    public boolean needsSticker() {
        return this == POLL || this == QUIZ || this == QUESTION || this == LINK;
    }
}
