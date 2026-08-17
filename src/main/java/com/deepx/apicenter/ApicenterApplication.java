package com.deepx.apicenter;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.resilience.annotation.EnableResilientMethods;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@EnableResilientMethods
public class ApicenterApplication {

    public static void main(String[] args) {
        SpringApplication.run(ApicenterApplication.class, args);
    }

}
