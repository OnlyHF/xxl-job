package com.xxl.job.admin;

import com.xxl.job.admin.springboot.UserDemo;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * @author xuxueli 2018-10-28 00:38:13
 */
@SpringBootApplication
public class XxlJobAdminApplication {

	public static void main(String[] args) {
		ConfigurableApplicationContext run = SpringApplication.run(XxlJobAdminApplication.class, args);
		System.out.println(run.getBean(UserDemo.class));
	}

}