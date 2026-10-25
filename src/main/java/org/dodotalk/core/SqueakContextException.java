package org.dodotalk.core;

/**
 * Thrown when the VM encounters a context it cannot make sense of, e.g. a
 * context without a home context or a method without bytecode.
 * <p>
 * Previously such situations were logged with a bare {@code System.out.println
 * ("error")} and the VM kept running with a half-initialised state, which
 * turned a diagnosable problem into an unrelated
 * {@link NullPointerException} several frames later.
 */
public class SqueakContextException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public SqueakContextException(String message) {
        super(message);
    }

    public SqueakContextException(String message, Throwable cause) {
        super(message, cause);
    }
}
