package org.dodotalk.core;

import java.io.IOException;

/**
 * Thrown when an image file is malformed, truncated or otherwise cannot be
 * interpreted by {@link SqueakImage}.
 * <p>
 * This is a checked {@link IOException} on purpose: loading an image is an I/O
 * operation and the failure has to be reported to the caller (the VM
 * bootstrap) instead of being swallowed deeper in the stack. Phase 4 of the
 * maintenance plan will unify the remaining error handling around a similar
 * dedicated exception hierarchy.
 */
public class SqueakImageFormatException extends IOException {

    private static final long serialVersionUID = 1L;

    public SqueakImageFormatException(String message) {
        super(message);
    }

    public SqueakImageFormatException(String message, Throwable cause) {
        super(message, cause);
    }
}
