/**
 * 03 Authoring module. See docs/ai/01-architecture.md §1.5 and §1.8.
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "03 Authoring",
        allowedDependencies = {"shared", "identity :: api"})
package com.edumy.authoring;
