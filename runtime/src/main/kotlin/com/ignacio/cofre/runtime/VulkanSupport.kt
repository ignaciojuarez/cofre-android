package com.ignacio.cofre.runtime

import android.util.Log

/** What the device's Vulkan driver offers, measured against Minecraft 26.2's Vulkan backend. */
data class VulkanReport(
    /** Device API version, like "1.3.128"; empty when there's no Vulkan at all. */
    val apiVersion: String,
    val deviceName: String,
    val driverVersion: String,
    /** Each required extension → whether the device has it. */
    val extensions: Map<String, Boolean>,
    /** Each required feature → whether the device has it. */
    val features: Map<String, Boolean>,
    /** Everything required that's absent: "Vulkan 1.2", extension names, feature names. */
    val missing: List<String>,
    /** Set when Vulkan couldn't be queried at all (no libvulkan, no device). */
    val error: String? = null,
) {
    val suitable: Boolean get() = error == null && missing.isEmpty()
}

object VulkanSupport {
    /** Vulkan 1.2 plus these, from com.mojang.blaze3d.vulkan in the 26.2 client. */
    val requiredExtensions: List<String> = listOf(
        "VK_KHR_dynamic_rendering",
        "VK_KHR_push_descriptor",
        "VK_KHR_synchronization2",
        "VK_EXT_vertex_attribute_divisor",
        "VK_KHR_swapchain",
    )
    val requiredFeatures: List<String> = listOf(
        "multiDrawIndirect",
        "fillModeNonSolid",
        "samplerAnisotropy",
        "shaderDrawParameters",
        "timelineSemaphore",
    )
    private const val REQUIRED_API = (1 shl 22) or (2 shl 12) // VK_API_VERSION_1_2

    /** Queries the driver (a few ms; fine on the main thread) and logs the result. */
    fun check(): VulkanReport {
        System.loadLibrary("cofre")
        val report = parse(nativeProbe())
        Log.i(TAG, "[Cofre] vulkan check suitable=${report.suitable} missing=${report.missing}")
        Log.i(TAG, "[Cofre] vulkan device=${report.deviceName} api=${report.apiVersion} driver=${report.driverVersion}" +
            (report.error?.let { " error=$it" } ?: ""))
        return report
    }

    internal fun parse(raw: String): VulkanReport {
        var api = 0L
        var driver = 0L
        var name = ""
        var error: String? = null
        val extensions = HashSet<String>()
        val features = HashMap<String, Boolean>()
        for (line in raw.lineSequence()) {
            val key = line.substringBefore('=')
            val value = line.substringAfter('=', "")
            when (key) {
                "api" -> api = value.toLong()
                "driver" -> driver = value.toLong()
                "name" -> name = value
                "ext" -> extensions += value
                "feature" -> features[value.substringBefore('=')] = value.substringAfter('=') == "1"
                "error" -> error = value
            }
        }
        val extensionReport = requiredExtensions.associateWith { it in extensions }
        val featureReport = requiredFeatures.associateWith { features[it] == true }
        val missing = buildList {
            if (api < REQUIRED_API) add("Vulkan 1.2")
            extensionReport.filterValues { !it }.keys.forEach(::add)
            featureReport.filterValues { !it }.keys.forEach(::add)
        }
        return VulkanReport(
            apiVersion = if (error == null) "${api shr 22 and 0x7f}.${api shr 12 and 0x3ff}.${api and 0xfff}" else "",
            deviceName = name,
            // Vendor-defined; most (Qualcomm, Arm, Mesa) use VK_MAKE_VERSION's 10-bit major, e.g. 512.744.0.
            driverVersion = if (error == null) "${driver shr 22}.${driver shr 12 and 0x3ff}.${driver and 0xfff}" else "",
            extensions = extensionReport,
            features = featureReport,
            missing = missing,
            error = error,
        )
    }

    @JvmStatic private external fun nativeProbe(): String
}

internal const val TAG = "Cofre"
