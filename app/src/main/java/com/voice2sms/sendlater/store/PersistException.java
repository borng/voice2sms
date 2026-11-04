package com.voice2sms.sendlater.store;

/** A schedule could not be verified persisted (R21): the caller shows an error and touches nothing. */
public class PersistException extends Exception {
    public PersistException(String message) { super(message); }
    public PersistException(String message, Throwable cause) { super(message, cause); }
}
