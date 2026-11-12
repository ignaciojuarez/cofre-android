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
        // Up to 1.3: the device's 1.2 and 1.3 feature structs are only valid to query on an instance that new.
        .apiVersion = instance_version < VK_API_VERSION_1_3 ? instance_version : VK_API_VERSION_1_3,
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
    int has_timeline = 0, has_host_query_reset = 0, has_sync2 = 0, has_dynamic_rendering = 0, has_divisor = 0;
    if (exts && vkEnumerateDeviceExtensionProperties(device, NULL, &ext_count, exts) == VK_SUCCESS) {
        for (uint32_t i = 0; i < ext_count; i++) {
            const char *name = exts[i].extensionName;
            put(out, "ext=%s\n", name);
            has_timeline |= !strcmp(name, VK_KHR_TIMELINE_SEMAPHORE_EXTENSION_NAME);
            has_host_query_reset |= !strcmp(name, VK_EXT_HOST_QUERY_RESET_EXTENSION_NAME);
            has_sync2 |= !strcmp(name, VK_KHR_SYNCHRONIZATION_2_EXTENSION_NAME);
            has_dynamic_rendering |= !strcmp(name, VK_KHR_DYNAMIC_RENDERING_EXTENSION_NAME);
            has_divisor |= !strcmp(name, VK_EXT_VERTEX_ATTRIBUTE_DIVISOR_EXTENSION_NAME);
        }
    }
    free(exts);

    // The features VulkanBackend.REQUIRED_DEVICE_FEATURES lists. Each struct goes in the chain only when the device
    // has its Vulkan version or extension: drivers may not expect the others.
    uint32_t api = props.apiVersion < app.apiVersion ? props.apiVersion : app.apiVersion;
    VkPhysicalDeviceShaderDrawParametersFeatures draw_params = {.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_SHADER_DRAW_PARAMETERS_FEATURES};
    VkPhysicalDeviceTimelineSemaphoreFeatures timeline = {.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_TIMELINE_SEMAPHORE_FEATURES};
    VkPhysicalDeviceHostQueryResetFeatures host_query_reset = {.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_HOST_QUERY_RESET_FEATURES};
    VkPhysicalDeviceSynchronization2Features sync2 = {.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_SYNCHRONIZATION_2_FEATURES};
    VkPhysicalDeviceDynamicRenderingFeatures dynamic_rendering = {.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_DYNAMIC_RENDERING_FEATURES};
    VkPhysicalDeviceVertexAttributeDivisorFeaturesEXT divisor = {.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VERTEX_ATTRIBUTE_DIVISOR_FEATURES_EXT};
    VkPhysicalDeviceFeatures2 features = {.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2};
    if (vkGetPhysicalDeviceFeatures2 && api >= VK_API_VERSION_1_1) {
        void **next = &features.pNext;
#define CHAIN(s, present) if (present) { *next = &s; next = &s.pNext; }
        CHAIN(draw_params, 1)
        CHAIN(timeline, api >= VK_API_VERSION_1_2 || has_timeline)
        CHAIN(host_query_reset, api >= VK_API_VERSION_1_2 || has_host_query_reset)
        CHAIN(sync2, api >= VK_API_VERSION_1_3 || has_sync2)
        CHAIN(dynamic_rendering, api >= VK_API_VERSION_1_3 || has_dynamic_rendering)
        CHAIN(divisor, has_divisor)
#undef CHAIN
        vkGetPhysicalDeviceFeatures2(device, &features);
    } else {
        vkGetPhysicalDeviceFeatures(device, &features.features);
    }
    put(out, "feature=multiDrawIndirect=%d\n", features.features.multiDrawIndirect);
    put(out, "feature=fillModeNonSolid=%d\n", features.features.fillModeNonSolid);
    put(out, "feature=samplerAnisotropy=%d\n", features.features.samplerAnisotropy);
    put(out, "feature=shaderDrawParameters=%d\n", draw_params.shaderDrawParameters);
    put(out, "feature=timelineSemaphore=%d\n", timeline.timelineSemaphore);
    put(out, "feature=hostQueryReset=%d\n", host_query_reset.hostQueryReset);
    put(out, "feature=synchronization2=%d\n", sync2.synchronization2);
    put(out, "feature=dynamicRendering=%d\n", dynamic_rendering.dynamicRendering);
    put(out, "feature=vertexAttributeInstanceRateDivisor=%d\n", divisor.vertexAttributeInstanceRateDivisor);

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
