package com.hashcode.web;

import com.hashcode.streaming.experiments.ScientificExperimentRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import java.util.Arrays;

@SpringBootApplication
public class WebApplication {
    public static void main(String[] args) throws Exception {
        if (args.length > 0 && args[0].equals("--worker")) {
            ScientificExperimentRunner.main(Arrays.copyOfRange(args, 1, args.length));
            return;
        }
        SpringApplication.run(WebApplication.class, args);
    }
}
