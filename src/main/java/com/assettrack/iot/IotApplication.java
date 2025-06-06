package com.assettrack.iot;

import com.assettrack.iot.protocol.ProtocolDetectionHandler;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.util.List;



@SpringBootApplication
@EnableAsync
@ComponentScan(basePackages = {
		"com.assettrack.iot.protocol",
		"com.assettrack.iot.service",
		"com.assettrack.iot.handler.network",
		"com.assettrack.iot.session"
})
public class IotApplication {

	public static void main(String[] args) {
		SpringApplication.run(IotApplication.class, args);
	}

	@Autowired
	private List<ProtocolDetectionHandler> allHandlers;

	@PostConstruct
	public void verifyBeans() {
		System.out.println(">>>> Number of ProtocolDetectionHandler beans = " + allHandlers.size());
		for (ProtocolDetectionHandler h : allHandlers) {
			System.out.println(">>>> Handler ID: " + System.identityHashCode(h));
		}
	}

}
