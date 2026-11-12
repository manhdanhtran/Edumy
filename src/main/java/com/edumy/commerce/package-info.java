/**
 * 04 Commerce module. See docs/ai/01-architecture.md §1.5 and §1.8.
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "04 Commerce",
        allowedDependencies = {"shared", "identity :: api", "authoring :: api", "learning :: api"})
package com.edumy.commerce;
