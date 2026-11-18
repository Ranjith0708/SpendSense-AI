package com.finance.analyzer;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class FinanceAnalyzerApplication {
    public static void main(String[] args) {
        SpringApplication.run(FinanceAnalyzerApplication.class, args);
    }
}
