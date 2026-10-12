package leads.ruleengine.core;

import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurationPackage;

/**
 * Minimal boot configuration for the library's slice tests (the library itself has no
 * application class). Registers this package so entities and repositories are found.
 */
@SpringBootConfiguration
@AutoConfigurationPackage
public class TestApplication {
}
