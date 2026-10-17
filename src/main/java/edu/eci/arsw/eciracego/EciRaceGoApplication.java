package edu.eci.arsw.eciracego;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class EciRaceGoApplication {

	public static void main(String[] args) {

		SpringApplication.run(EciRaceGoApplication.class, args);
	}


}
