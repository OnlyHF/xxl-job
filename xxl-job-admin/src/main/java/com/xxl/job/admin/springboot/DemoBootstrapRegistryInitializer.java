package com.xxl.job.admin.springboot;

import org.springframework.boot.bootstrap.BootstrapRegistry;
import org.springframework.boot.bootstrap.BootstrapRegistryInitializer;
import org.springframework.util.ObjectUtils;

public class DemoBootstrapRegistryInitializer implements BootstrapRegistryInitializer {

    @Override
    public void initialize(BootstrapRegistry registry) {
        registry.register(UserDemo.class, context -> new UserDemo("xxl-job", "xxl-job"));

        registry.addCloseListener(event -> {
            UserDemo userDemo = event.getBootstrapContext().get(UserDemo.class);
            if (!ObjectUtils.isEmpty(userDemo)) {
                event.getApplicationContext().getBeanFactory().registerSingleton("userDemo", userDemo);
            }
        });
    }

}
