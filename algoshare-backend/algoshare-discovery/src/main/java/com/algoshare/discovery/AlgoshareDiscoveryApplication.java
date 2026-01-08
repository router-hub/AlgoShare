package com.algoshare.discovery;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.netflix.eureka.server.EnableEurekaServer;

@SpringBootApplication
@EnableEurekaServer
public class AlgoshareDiscoveryApplication {

  public static void main(String[] args) {
    SpringApplication.run(AlgoshareDiscoveryApplication.class, args);
  }
}
