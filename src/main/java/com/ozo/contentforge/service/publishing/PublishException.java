package com.ozo.contentforge.service.publishing;

/** A publishing failure with Instagram's own explanation; {@code retryable} when trying again later may succeed. */
public class PublishException extends RuntimeException {

    private final boolean retryable;

    public PublishException(String message, boolean retryable) {
        super(message);
        this.retryable = retryable;
    }

    public boolean isRetryable() {
        return retryable;
    }
}
