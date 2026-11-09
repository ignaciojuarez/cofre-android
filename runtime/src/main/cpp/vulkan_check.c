// Reads what the device's Vulkan driver offers, for VulkanSupport.check(). Loads libvulkan.so
// itself so a device without Vulkan gets a report, not a crash. Kotlin compares the result with
// Minecraft 26.2's requirements.
#define VK_NO_PROTOTYPES
#include <vulkan/vulkan.h>

#include <dlfcn.h>
#include <jni.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

typedef struct {
    char *data;
    size_t len, cap;
} text;

static void put(text *t, const char *fmt, ...) {
    va_list args;
    va_start(args, fmt);
    char line[512];
    int n = vsnprintf(line, sizeof line, fmt, args);
    va_end(args);
    if (n < 0) return;
    if ((size_t) n >= sizeof line) n = sizeof line - 1;
    if (t->len + n + 1 > t->cap) {
        size_t cap = (t->cap + n + 1) * 2;
        char *grown = realloc(t->data, cap);
        if (!grown) return;
        t->data = grown;
        t->cap = cap;
    }
    memcpy(t->data + t->len, line, n + 1);
    t->len += n;
}

#define LOAD(name) PFN_##name name = (PFN_##name) gipa(instance, #name)

// One "key=value" per line: api, driver, vendor, name, ext (repeated), feature=<name>=0|1, or error.
static void probe(text *out) {
    void *lib = dlopen("libvulkan.so", RTLD_NOW | RTLD_LOCAL);
    if (!lib) {
        put(out, "error=libvulkan.so not found\n");
        return;
    }
    PFN_vkGetInstanceProcAddr gipa = (PFN_vkGetInstanceProcAddr) dlsym(lib, "vkGetInstanceProcAddr");
    VkInstance instance = VK_NULL_HANDLE;
    if (!gipa) {
        put(out, "error=vkGetInstanceProcAddr missing\n");
        return;
    }
    uint32_t instance_version = VK_API_VERSION_1_0;
    LOAD(vkEnumerateInstanceVersion);
    if (vkEnumerateInstanceVersion) vkEnumerateInstanceVersion(&instance_version);
    LOAD(vkCreateInstance);
    VkApplicationInfo app = {
        .sType = VK_STRUCTURE_TYPE_APPLICATION_INFO,
        .pApplicationName = "Cofre",
        .apiVersion = instance_version >= VK_API_VERSION_1_1 ? VK_API_VERSION_1_1 : VK_API_VERSION_1_0,
    };
    VkInstanceCreateInfo create = {.sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO, .pApplicationInfo = &app};
    VkResult result = vkCreateInstance(&create, NULL, &instance);
    if (result != VK_SUCCESS) {
        put(out, "error=vkCreateInstance failed (%d)\n", result);
        return;
    }
    LOAD(vkDestroyInstance);
    LOAD(vkEnumeratePhysicalDevices);
    LOAD(vkGetPhysicalDeviceProperties);
    LOAD(vkGetPhysicalDeviceFeatures);
    LOAD(vkGetPhysicalDeviceFeatures2);
    LOAD(vkEnumerateDeviceExtensionProperties);

    uint32_t count = 1;
    VkPhysicalDevice device = VK_NULL_HANDLE;
    result = vkEnumeratePhysicalDevices(instance, &count, &device); // Android has one GPU; take the first
    if ((result != VK_SUCCESS && result != VK_INCOMPLETE) || count == 0) {
        put(out, "error=no Vulkan device\n");
        vkDestroyInstance(instance, NULL);
        return;
    }

    VkPhysicalDeviceProperties props;
    vkGetPhysicalDeviceProperties(device, &props);
    put(out, "api=%u\ndriver=%u\nvendor=%u\nname=%s\n", props.apiVersion, props.driverVersion, props.vendorID, props.deviceName);

    uint32_t ext_count = 0;
    vkEnumerateDeviceExtensionProperties(device, NULL, &ext_count, NULL);
    VkExtensionProperties *exts = calloc(ext_count ? ext_count : 1, sizeof *exts);
    int has_timeline_ext = 0;
    if (exts && vkEnumerateDeviceExtensionProperties(device, NULL, &ext_count, exts) == VK_SUCCESS) {
        for (uint32_t i = 0; i < ext_count; i++) {
            put(out, "ext=%s\n", exts[i].extensionName);
            if (!strcmp(exts[i].extensionName, VK_KHR_TIMELINE_SEMAPHORE_EXTENSION_NAME)) has_timeline_ext = 1;
        }
    }
    free(exts);

    VkPhysicalDeviceTimelineSemaphoreFeatures timeline = {.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_TIMELINE_SEMAPHORE_FEATURES};
    VkPhysicalDeviceShaderDrawParametersFeatures draw_params = {.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_SHADER_DRAW_PARAMETERS_FEATURES};
    VkPhysicalDeviceFeatures2 features = {.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2};
    if (vkGetPhysicalDeviceFeatures2 && app.apiVersion >= VK_API_VERSION_1_1 && props.apiVersion >= VK_API_VERSION_1_1) {
        features.pNext = &draw_params;
        if (props.apiVersion >= VK_API_VERSION_1_2 || has_timeline_ext) draw_params.pNext = &timeline;
        vkGetPhysicalDeviceFeatures2(device, &features);
    } else {
        vkGetPhysicalDeviceFeatures(device, &features.features);
    }
    put(out, "feature=multiDrawIndirect=%d\n", features.features.multiDrawIndirect);
    put(out, "feature=fillModeNonSolid=%d\n", features.features.fillModeNonSolid);
    put(out, "feature=samplerAnisotropy=%d\n", features.features.samplerAnisotropy);
    put(out, "feature=shaderDrawParameters=%d\n", draw_params.shaderDrawParameters);
    put(out, "feature=timelineSemaphore=%d\n", timeline.timelineSemaphore);

    vkDestroyInstance(instance, NULL);
}

JNIEXPORT jstring JNICALL Java_com_ignacio_cofre_runtime_VulkanSupport_nativeProbe(JNIEnv *env, jclass clazz) {
    (void) clazz;
    text out = {0};
    probe(&out);
    jstring result = (*env)->NewStringUTF(env, out.data ? out.data : "error=out of memory\n");
    free(out.data);
    return result;
}
