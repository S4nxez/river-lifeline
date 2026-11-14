package com.riverlifeline.pipeline.infrastructure.adapter.out.jdbc;

import com.riverlifeline.pipeline.application.port.out.Transactions;
import java.util.function.Supplier;
import org.springframework.transaction.support.TransactionTemplate;

/** One database transaction per unit of work. */
public class SpringTransactions implements Transactions {
    private final TransactionTemplate template;

    public SpringTransactions(TransactionTemplate template) {
        this.template = template;
    }

    @Override
    public <T> T inTransaction(Supplier<T> work) {
        return template.execute(status -> work.get());
    }
}
