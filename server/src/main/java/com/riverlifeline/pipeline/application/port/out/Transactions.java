package com.riverlifeline.pipeline.application.port.out;

import java.util.function.Supplier;

/** Runs work in one database transaction: all of it is stored, or none. */
public interface Transactions {
    <T> T inTransaction(Supplier<T> work);

    default void run(Runnable work) {
        inTransaction(() -> {
            work.run();
            return null;
        });
    }
}
