package com.pms.projectservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class ProjectServiceApplication {

	public static void main(String[] args) {
		SpringApplication.run(ProjectServiceApplication.class, args);
		System.out.println("Project service is Running");
	}

}
