package com.systemdesign.Idempotency;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class IdempotencyApplication {

	public static void main(String[] args) {
		System.out.println(
				"Java timezone = " +
						java.util.TimeZone.getDefault().getID()
		);
		SpringApplication.run(IdempotencyApplication.class, args);
	}

}
