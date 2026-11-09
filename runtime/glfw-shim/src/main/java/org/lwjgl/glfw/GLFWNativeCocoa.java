/* Cofre's GLFW for Android: there's no Cocoa here. Signatures follow LWJGL 3.4.1 (BSD-3-Clause). */
package org.lwjgl.glfw;

public class GLFWNativeCocoa {
    protected GLFWNativeCocoa() {
        throw new UnsupportedOperationException();
    }
    public static int glfwGetCocoaMonitor(long monitor) {
        return 0;
    }
    public static long glfwGetCocoaWindow(long window) {
        return 0L;
    }
    public static long glfwGetCocoaView(long window) {
        return 0L;
    }
}
