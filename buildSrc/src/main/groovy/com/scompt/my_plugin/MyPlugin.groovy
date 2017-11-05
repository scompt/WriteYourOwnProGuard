package com.scompt.my_plugin

import org.gradle.api.Plugin
import org.gradle.api.Project

@SuppressWarnings("GroovyUnusedDeclaration")
class MyPlugin implements Plugin<Project> {
    @Override
    void apply(Project p) {
        def t = new WriteYourOwnProGuardTransform(p)
        p.android.registerTransform(t)
    }
}
