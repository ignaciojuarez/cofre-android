package com.ignacio.cofre.runtime

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VulkanSupportTest {
    private fun probe(api: String, extensions: List<String>, features: Map<String, Int>) = buildString {
        append("api=$api\ndriver=${(512L shl 22) or (744L shl 12)}\nvendor=65541\nname=Test GPU\n")
        extensions.forEach { append("ext=$it\n") }
        features.forEach { (name, on) -> append("feature=$name=$on\n") }
    }

    private val allFeatures = VulkanSupport.requiredFeatures.associateWith { 1 }

    @Test
    fun deviceWithEverythingIsSuitable() {
        val report = VulkanSupport.parse(probe("${(1 shl 22) or (3 shl 12)}", VulkanSupport.requiredExtensions + "VK_KHR_maintenance1", allFeatures))
        assertTrue(report.suitable)
        assertEquals("1.3.0", report.apiVersion)
        assertEquals("512.744.0", report.driverVersion)
        assertEquals("Test GPU", report.deviceName)
    }

    @Test
    fun missingListsVersionExtensionsAndFeaturesInOrder() {
        val extensions = VulkanSupport.requiredExtensions - "VK_KHR_push_descriptor" - "VK_EXT_vertex_attribute_divisor" + "VK_KHR_vertex_attribute_divisor"
        val features = allFeatures + ("timelineSemaphore" to 0) + ("dynamicRendering" to 0) - "vertexAttributeInstanceRateDivisor"
        val report = VulkanSupport.parse(probe("${(1 shl 22) or (1 shl 12)}", extensions, features))
        assertFalse(report.suitable)
        assertEquals(
            listOf("Vulkan 1.2", "VK_KHR_push_descriptor", "VK_EXT_vertex_attribute_divisor", "timelineSemaphore", "dynamicRendering", "vertexAttributeInstanceRateDivisor"),
            report.missing,
        )
        assertEquals(false, report.extensions["VK_EXT_vertex_attribute_divisor"])
        assertEquals(false, report.features["vertexAttributeInstanceRateDivisor"]) // not probed (no extension): missing
    }

    @Test
    fun checksEveryFeatureMinecraft262Requires() {
        // VulkanBackend.REQUIRED_DEVICE_FEATURES in the 26.2 client (javap -c -p).
        val minecraft = listOf(
            "multiDrawIndirect", "fillModeNonSolid", "samplerAnisotropy", "shaderDrawParameters", "timelineSemaphore",
            "hostQueryReset", "synchronization2", "dynamicRendering", "vertexAttributeInstanceRateDivisor",
        )
        assertEquals(minecraft, VulkanSupport.requiredFeatures)
    }

    @Test
    fun noVulkanIsAnErrorNotACrash() {
        val report = VulkanSupport.parse("error=libvulkan.so not found\n")
        assertFalse(report.suitable)
        assertEquals("libvulkan.so not found", report.error)
    }
}
