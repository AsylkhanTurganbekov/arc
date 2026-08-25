package kz.belesai.arc;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication
public class ArcApplication {
    public static void main(String[] args) {
        SpringApplication.run(ArcApplication.class, args);
    }
}
