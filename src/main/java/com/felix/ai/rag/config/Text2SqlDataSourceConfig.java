package com.felix.ai.rag.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.DataSourceInitializer;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import javax.sql.DataSource;

/**
 * Text2SQL 数据源配置
 * 初始化H2内存数据库和示例数据
 */
@Configuration
@Slf4j
public class Text2SqlDataSourceConfig {

    /**
     * 初始化数据库
     * 创建示例表和数据
     */
    @Bean
    public DataSourceInitializer dataSourceInitializer(DataSource dataSource) {
        DataSourceInitializer initializer = new DataSourceInitializer();
        initializer.setDataSource(dataSource);

        ResourceDatabasePopulator populator = new ResourceDatabasePopulator();

        // 创建示例表
        populator.addScript(new ClassPathResource("text2sql/schema.sql"));

        // 插入示例数据
        populator.addScript(new ClassPathResource("text2sql/data.sql"));

        initializer.setDatabasePopulator(populator);

        log.info("Text2SQL数据库初始化配置完成");
        return initializer;
    }
}
