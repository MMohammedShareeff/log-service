package com.example.log_service.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import com.zaxxer.hikari.HikariDataSource
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.jdbc.core.JdbcTemplate
import javax.sql.DataSource
import java.time.Clock

@Configuration
 class ConfigLayer {
    @Bean
    fun clock(): Clock = Clock.systemUTC()

   
     @Bean(defaultCandidate = false)
     @ConfigurationProperties("app.datasource.read")
     fun readDataSource(): HikariDataSource = HikariDataSource()
 
     @Bean(defaultCandidate = false)
     fun readJdbcTemplate(@Qualifier("readDataSource") datasource: DataSource): JdbcTemplate =
        JdbcTemplate(datasource)
 }