/* Cofre's GLFW for Android. Signatures follow LWJGL 3.4.1 (BSD-3-Clause, see NOTICE). */
package org.lwjgl.glfw;

public final class Callbacks {
    private Callbacks() {}

    /** Clears every window callback. Ours are plain Java objects, so there's nothing native to free. */
    public static void glfwFreeCallbacks(long window) {
        GLFW.glfwSetWindowPosCallback(window, null);
        GLFW.glfwSetWindowSizeCallback(window, null);
        GLFW.glfwSetWindowCloseCallback(window, null);
        GLFW.glfwSetWindowRefreshCallback(window, null);
        GLFW.glfwSetWindowFocusCallback(window, null);
        GLFW.glfwSetWindowIconifyCallback(window, null);
        GLFW.glfwSetWindowMaximizeCallback(window, null);
        GLFW.glfwSetFramebufferSizeCallback(window, null);
        GLFW.glfwSetWindowContentScaleCallback(window, null);
        GLFW.glfwSetKeyCallback(window, null);
        GLFW.glfwSetCharCallback(window, null);
        GLFW.glfwSetCharModsCallback(window, null);
        GLFW.glfwSetMouseButtonCallback(window, null);
        GLFW.glfwSetCursorPosCallback(window, null);
        GLFW.glfwSetCursorEnterCallback(window, null);
        GLFW.glfwSetScrollCallback(window, null);
        GLFW.glfwSetDropCallback(window, null);
        GLFW.glfwSetIMEStatusCallback(window, null);
        GLFW.glfwSetPreeditCallback(window, null);
        GLFW.glfwSetPreeditCandidateCallback(window, null);
    }
}
