package com.wex.currencytranswexlator;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class CurrencyTransWEXlatorApplication {
    public static void main(String[] args) {
        SpringApplication.run(CurrencyTransWEXlatorApplication.class, args);
    }
}
