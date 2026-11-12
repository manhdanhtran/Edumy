/**
 * 05 Learning module. See docs/ai/01-architecture.md §1.5 and §1.8.
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "05 Learning",
        allowedDependencies = {"shared", "identity :: api", "authoring :: api"})
package com.edumy.learning;
