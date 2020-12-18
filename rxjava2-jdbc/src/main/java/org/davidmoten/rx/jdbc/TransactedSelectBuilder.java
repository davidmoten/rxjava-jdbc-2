package org.davidmoten.rx.jdbc;

import java.sql.Connection;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import javax.annotation.Nonnull;

import io.reactivex.Flowable;
import io.reactivex.Single;

public final class TransactedSelectBuilder implements DependsOn<TransactedSelectBuilder>, GetterTx {

    private final SelectBuilder selectBuilder;

    private boolean valuesOnly = false;

    private final Database db;

    TransactedSelectBuilder(SelectBuilder selectBuilder, Database db) {
        this.selectBuilder = selectBuilder;
        this.db = db;
    }

    public TransactedSelectBuilder parameters(@Nonnull Flowable<List<Object>> parameters) {
        selectBuilder.parameters(parameters);
        return this;
    }

    public TransactedSelectBuilder parameters(@Nonnull List<?> values) {
        selectBuilder.parameters(values);
        return this;
    }

    public TransactedSelectBuilder parameter(@Nonnull String name, Object value) {
        selectBuilder.parameter(name, value);
        return this;
    }

    public TransactedSelectBuilder parameters(@Nonnull Object... values) {
        selectBuilder.parameters(values);
        return this;
    }

    public TransactedSelectBuilder parameter(Object value) {
        return parameters(value);
    }

    public TransactedSelectBuilder fetchSize(int size) {
        selectBuilder.fetchSize(size);
        return this;
    }

    public TransactedSelectBuilder transactedValuesOnly() {
        this.valuesOnly = true;
        return this;
    }

    @Override
    public TransactedSelectBuilder dependsOn(@Nonnull Flowable<?> flowable) {
        selectBuilder.dependsOn(flowable);
        return this;
    }

    public TransactedSelectBuilderValuesOnly valuesOnly() {
        return new TransactedSelectBuilderValuesOnly(this, db);
    }

    public static final class TransactedSelectBuilderValuesOnly implements Getter {
        private final TransactedSelectBuilder b;
        private final Database db;

        TransactedSelectBuilderValuesOnly(TransactedSelectBuilder b, Database db) {
            this.b = b;
            this.db = db;
        }

        public <T> Flowable<T> get(@Nonnull ResultSetMapper<? extends T> function) {
            return createFlowable(b.selectBuilder, function, db) //
                    .flatMap(Tx.flattenToValuesOnly());
        }

    }

    @Override
    public <T> Flowable<Tx<T>> get(ResultSetMapper<? extends T> function) {
        Flowable<Tx<T>> o = createFlowable(selectBuilder, function, db);
        if (valuesOnly) {
            return o.filter(tx -> tx.isValue());
        } else {
            return o;
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> Flowable<Tx<T>> createFlowable(SelectBuilder sb,
            ResultSetMapper<? extends T> mapper, Database db) {
        return (Flowable<Tx<T>>) (Flowable<?>) Flowable.defer(() -> {
            //Select.<T>create(connection, pg, sql, fetchSize, mapper, true);
            AtomicReference<Connection> connection = new AtomicReference<Connection>();
            Single<Connection> con = sb.connection //
                    .map(c -> Util.toTransactedConnection(connection, c));
            return Select.create(con, //
                    sb.parameterGroupsToFlowable(), //
                    sb.sql, //
                    sb.fetchSize, //
                    mapper, //
                    false, //
                    sb.queryTimeoutSec) //
                    .materialize() //
                    .flatMap(n -> Tx.toTx(n, connection.get(), db)) //
                    .doOnNext(tx -> {
                        if (tx.isComplete()) {
                            ((TxImpl<T>) tx).connection().commit();
                            ((TxImpl<T>) tx).connection().close();
                        }
                    });
        });
    }

}
