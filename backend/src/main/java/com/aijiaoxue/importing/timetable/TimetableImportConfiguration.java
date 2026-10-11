package com.aijiaoxue.importing.timetable;

import java.util.Properties;
import org.apache.ibatis.mapping.DatabaseIdProvider;
import org.apache.ibatis.mapping.VendorDatabaseIdProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class TimetableImportConfiguration {
    @Bean HtmlTimetableParser htmlTimetableParser() { return new HtmlTimetableParser(); }

    @Bean @ConditionalOnMissingBean
    ImportTransactionCheckpoint importTransactionCheckpoint() {
        return new ImportTransactionCheckpoint();
    }

    @Bean
    DatabaseIdProvider databaseIdProvider() {
        var provider = new VendorDatabaseIdProvider();
        var names = new Properties();
        names.setProperty("H2", "h2");
        names.setProperty("MySQL", "mysql");
        provider.setProperties(names);
        return provider;
    }
}
