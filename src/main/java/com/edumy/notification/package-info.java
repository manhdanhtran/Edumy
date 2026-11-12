/**
 * Notification module. See docs/ai/01-architecture.md §1.5 and §1.8.
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "Notification",
        allowedDependencies = {"shared", "identity :: api", "authoring :: api", "commerce :: api", "learning :: api"})
package com.edumy.notification;
