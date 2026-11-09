package com.ignacio.cofre.kit.mods

import kotlin.test.*

class RemovalTest {
    private fun node(id: String, vararg deps: String, direct: Boolean = false, protected: Boolean = false) = InstalledMod("$id.jar", id, id, dependencies = deps.map { JarDependency(it) }, installedDirectly = direct, protectedFromRemoval = protected)
    @Test fun sharedDependencyStays() {
        assertTrue(ModRemoval.options("a", listOf(node("a", "shared"), node("b", "shared"), node("shared"))).isEmpty())
    }
    @Test fun exclusiveDependencyIsOffered() {
        assertEquals(listOf("library.jar"), ModRemoval.options("a", listOf(node("a", "library"), node("library"))).map { it.file })
    }
    @Test fun exclusiveDependenciesRecurse() {
        assertEquals(listOf("b.jar", "c.jar"), ModRemoval.options("a", listOf(node("a", "b"), node("b", "c"), node("c"))).map { it.file })
    }
    @Test fun uncheckedParentKeepsItsDependency() {
        val nodes = listOf(node("a", "b"), node("b", "c"), node("c"))
        assertTrue(ModRemoval.selected("a", setOf("c.jar"), nodes).isEmpty())
        assertEquals(listOf("b.jar"), ModRemoval.selected("a", setOf("b.jar"), nodes).map { it.file })
    }
    @Test fun protectedAndDirectlyInstalledDependenciesStay() {
        for (dependency in listOf(node("library", direct = true), node("library", protected = true))) assertTrue(ModRemoval.options("a", listOf(node("a", "library"), dependency)).isEmpty())
    }
    @Test fun dependentsIncludeDisabledAndTransitiveUsers() {
        val nodes = listOf(node("a", "b").copy(enabled = false), node("b", "c"), node("c"))
        assertEquals(listOf("a.jar", "b.jar"), ModRemoval.dependents("c", nodes).map { it.file })
    }
    @Test fun cyclesAndSharedDeepDependenciesReachFixedPoint() {
        val nodes = listOf(node("a", "b", "c"), node("b", "d"), node("c", "d"), node("d"))
        assertEquals(setOf("b.jar", "c.jar", "d.jar"), ModRemoval.options("a", nodes).map { it.file }.toSet())
        assertTrue(ModRemoval.options("a", nodes + node("other", "d")).none { it.file == "d.jar" })
    }
}
