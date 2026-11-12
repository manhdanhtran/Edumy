package com.edumy;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;
import org.springframework.modulith.docs.Documenter;

/** Module boundaries (docs/ai/01-architecture.md §1.8, §1.13). Must stay green. */
class ModularityTest {

    static final ApplicationModules modules = ApplicationModules.of(EdumyApplication.class);

    @Test
    void verifiesModularStructure() {
        modules.verify();
    }

    @Test
    void writesDocumentation() {
        // Output: target/spring-modulith-docs
        new Documenter(modules).writeDocumentation();
    }
}
