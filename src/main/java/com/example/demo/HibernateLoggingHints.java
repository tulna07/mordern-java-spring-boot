package com.example.demo;

import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;
import org.springframework.context.annotation.ImportRuntimeHints;
import org.springframework.stereotype.Component;

@Component
@ImportRuntimeHints(HibernateLoggingHints.Registrar.class)
public class HibernateLoggingHints {

    static class Registrar implements RuntimeHintsRegistrar {

        private static final String[] LOGGER_IMPLS = {
            "org.hibernate.jpa.internal.JpaLogger_$logger",
            "org.hibernate.boot.BootLogging_$logger",
            "org.hibernate.internal.CoreMessageLogger_$logger",
            "org.hibernate.service.internal.ServiceLogger_$logger",
            "org.hibernate.internal.log.DeprecationLogger_$logger",
            "org.hibernate.orm.connections.pooling.ConnectionPoolingLogger_$logger",
            "org.hibernate.orm.jdbc.bind.BindingLogger_$logger",
            "org.hibernate.orm.model.mapping.MappingModelLogger_$logger",
            "org.hibernate.orm.query.QueryLogger_$logger",
            "org.hibernate.orm.schema.SchemaLogger_$logger",
            "org.hibernate.orm.transaction.TransactionLogger_$logger"
        };

        @Override
        public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
            for (String name : LOGGER_IMPLS) {
                try {
                    hints.reflection().registerType(
                        Class.forName(name, false, classLoader),
                        MemberCategory.INVOKE_DECLARED_CONSTRUCTORS,
                        MemberCategory.INVOKE_DECLARED_METHODS
                    );
                } catch (ClassNotFoundException ignored) {}
            }
        }
    }
}
