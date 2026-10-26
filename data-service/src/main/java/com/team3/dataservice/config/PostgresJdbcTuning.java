package com.team3.dataservice.config;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * PostgreSQL JDBC 드라이버 옵션. 접속 URL(DB_URL)을 운영·로컬마다 따로 쓰므로 URL이 아니라 여기서 붙인다.
 * reWriteBatchedInserts: 묶음 INSERT(hibernate.jdbc.batch_size)를 여러 행 INSERT 한 문장으로 바꿔 왕복을 줄인다.
 * PostgreSQL URL일 때만 붙인다 (테스트의 H2는 모르는 접속 옵션을 거부함).
 */
@Configuration(proxyBeanMethods = false)
public class PostgresJdbcTuning {

    @Bean
    static BeanPostProcessor postgresBatchedInserts() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessBeforeInitialization(Object bean, String beanName) {
                if (bean instanceof HikariDataSource hikari && hikari.getJdbcUrl() != null
                        && hikari.getJdbcUrl().startsWith("jdbc:postgresql:")) {
                    hikari.addDataSourceProperty("reWriteBatchedInserts", "true");
                }
                return bean;
            }
        };
    }
}
