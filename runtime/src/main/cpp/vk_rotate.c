// libcofrevk.so: the Vulkan library LWJGL loads in the :game process (-Dorg.lwjgl.vulkan.libname), in front of
// Android's libvulkan.so. LWJGL looks up only vkGetInstanceProcAddr here and gets every other function through it
// (device functions through the vkGetDeviceProcAddr that returns), so wrapping those two reaches anything.
//
// Minecraft 26.2 creates its swapchain with preTransform = currentTransform but never rotates what it draws, and sizes
// it from the window. When the display isn't in its natural orientation (a portrait-native panel shown in landscape,
// or landscape flipped 180°) the picture comes out rotated or upside down. We report IDENTITY, so Minecraft asks for
// that and Android's compositor rotates the picture. Android then returns SUBOPTIMAL on every present (preTransform !=
// the display's transform), which would make Minecraft rebuild its swapchain every frame: we hide that one.
#define VK_NO_PROTOTYPES
#include <vulkan/vulkan.h>

#include <dlfcn.h>
#include <stdatomic.h>
#include <string.h>

// ponytail: one instance and one device per process (Minecraft's), so the real functions live in globals.
static PFN_vkGetInstanceProcAddr real_get_instance_proc_addr;
static PFN_vkGetDeviceProcAddr real_get_device_proc_addr;
static PFN_vkGetPhysicalDeviceSurfaceCapabilitiesKHR real_caps;
static PFN_vkGetPhysicalDeviceSurfaceCapabilities2KHR real_caps2;
static PFN_vkAcquireNextImageKHR real_acquire;
static PFN_vkAcquireNextImage2KHR real_acquire2;
static PFN_vkQueuePresentKHR real_present;
static atomic_int rotated; // the display's real transform isn't IDENTITY

__attribute__((constructor)) static void load(void) {
    void *lib = dlopen("libvulkan.so", RTLD_NOW | RTLD_LOCAL);
    if (lib) real_get_instance_proc_addr = (PFN_vkGetInstanceProcAddr) dlsym(lib, "vkGetInstanceProcAddr");
}

// currentExtent stays: Android reports the window's size in the window's orientation (its pre-rotation guide swaps
// it to get the panel's), which is what an IDENTITY swapchain uses.
static void identity(VkSurfaceCapabilitiesKHR *caps) {
    rotated = caps->currentTransform != VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR;
    caps->currentTransform = VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR;
}

static VKAPI_ATTR VkResult VKAPI_CALL caps(VkPhysicalDevice gpu, VkSurfaceKHR surface, VkSurfaceCapabilitiesKHR *out) {
    VkResult result = real_caps(gpu, surface, out);
    if (result == VK_SUCCESS) identity(out);
    return result;
}

static VKAPI_ATTR VkResult VKAPI_CALL caps2(VkPhysicalDevice gpu, const VkPhysicalDeviceSurfaceInfo2KHR *info, VkSurfaceCapabilities2KHR *out) {
    VkResult result = real_caps2(gpu, info, out);
    if (result == VK_SUCCESS) identity(&out->surfaceCapabilities);
    return result;
}

static VkResult hide_suboptimal(VkResult result) {
    return result == VK_SUBOPTIMAL_KHR && rotated ? VK_SUCCESS : result;
}

static VKAPI_ATTR VkResult VKAPI_CALL acquire(VkDevice device, VkSwapchainKHR swapchain, uint64_t timeout, VkSemaphore semaphore,
                                              VkFence fence, uint32_t *index) {
    return hide_suboptimal(real_acquire(device, swapchain, timeout, semaphore, fence, index));
}

static VKAPI_ATTR VkResult VKAPI_CALL acquire2(VkDevice device, const VkAcquireNextImageInfoKHR *info, uint32_t *index) {
    return hide_suboptimal(real_acquire2(device, info, index));
}

static VKAPI_ATTR VkResult VKAPI_CALL present(VkQueue queue, const VkPresentInfoKHR *info) {
    VkResult result = real_present(queue, info);
    for (uint32_t i = 0; info->pResults && i < info->swapchainCount; i++) info->pResults[i] = hide_suboptimal(info->pResults[i]);
    return hide_suboptimal(result);
}

static VKAPI_ATTR PFN_vkVoidFunction VKAPI_CALL get_device_proc_addr(VkDevice device, const char *name);

// Our wrapper in place of each function above (keeping the real one); anything else as it is. A missing function
// stays missing.
#define HOOK(function, ours)                     \
    if (!strcmp(name, #function)) {              \
        real_##ours = (PFN_##function) real;     \
        return (PFN_vkVoidFunction) ours;        \
    }

static PFN_vkVoidFunction hook(const char *name, PFN_vkVoidFunction real) {
    if (!real) return NULL;
    HOOK(vkGetDeviceProcAddr, get_device_proc_addr)
    HOOK(vkGetPhysicalDeviceSurfaceCapabilitiesKHR, caps)
    HOOK(vkGetPhysicalDeviceSurfaceCapabilities2KHR, caps2)
    HOOK(vkAcquireNextImageKHR, acquire)
    HOOK(vkAcquireNextImage2KHR, acquire2)
    HOOK(vkQueuePresentKHR, present)
    return real;
}

static VKAPI_ATTR PFN_vkVoidFunction VKAPI_CALL get_device_proc_addr(VkDevice device, const char *name) {
    return hook(name, real_get_device_proc_addr(device, name));
}

__attribute__((visibility("default"))) VKAPI_ATTR PFN_vkVoidFunction VKAPI_CALL vkGetInstanceProcAddr(VkInstance instance, const char *name) {
    if (!real_get_instance_proc_addr) return NULL;
    if (!strcmp(name, "vkGetInstanceProcAddr")) return (PFN_vkVoidFunction) vkGetInstanceProcAddr;
    return hook(name, real_get_instance_proc_addr(instance, name));
}
