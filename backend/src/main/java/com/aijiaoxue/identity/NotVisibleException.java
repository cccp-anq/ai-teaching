package com.aijiaoxue.identity;

/** Missing and unauthorized objects intentionally share one external policy. */
public final class NotVisibleException extends RuntimeException {
    public NotVisibleException() { super("Object is not visible"); }
}
