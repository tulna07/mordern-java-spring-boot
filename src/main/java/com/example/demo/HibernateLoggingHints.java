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
            "org.hibernate.boot.archive.scan.internal.ScannerLogger_$logger",
            "org.hibernate.boot.jaxb.JaxbLogger_$logger",
            "org.hibernate.bytecode.enhance.spi.interceptor.BytecodeInterceptorLogging_$logger",
            "org.hibernate.cache.spi.SecondLevelCacheLogger_$logger",
            "org.hibernate.dialect.DialectLogging_$logger",
            "org.hibernate.engine.jdbc.JdbcLogging_$logger",
            "org.hibernate.engine.jdbc.batch.JdbcBatchLogging_$logger",
            "org.hibernate.engine.jdbc.env.internal.LobCreationLogging_$logger",
            "org.hibernate.engine.jdbc.spi.SQLExceptionLogging_$logger",
            "org.hibernate.id.enhanced.TableGeneratorLogger_$logger",
            "org.hibernate.internal.CoreMessageLogger_$logger",
            "org.hibernate.internal.SessionFactoryRegistryMessageLogger_$logger",
            "org.hibernate.internal.log.ConnectionInfoLogger_$logger",
            "org.hibernate.internal.log.DeprecationLogger_$logger",
            "org.hibernate.internal.log.IncubationLogger_$logger",
            "org.hibernate.internal.log.UrlMessageBundle_$logger",
            "org.hibernate.query.QueryLogging_$logger",
            "org.hibernate.resource.beans.internal.BeansMessageLogger_$logger",
            "org.hibernate.resource.jdbc.internal.ResourceRegistryLogger_$logger",
            "org.hibernate.resource.jdbc.internal.LogicalConnectionLogging_$logger",
            "org.hibernate.service.internal.ServiceLogger_$logger",
            "org.hibernate.validator.internal.util.logging.Log_$logger"
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
