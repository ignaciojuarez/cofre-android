package org.lwjgl.glfw;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.lang.reflect.Field;

import org.junit.jupiter.api.Test;

/**
 * Window handles without libcofre (in org.lwjgl.glfw to see the shim's state). LWJGL's callback interfaces need its
 * natives to load, so this checks which window owns the callbacks ({@link GLFW#currentWindow}), not the callbacks.
 */
class WindowHandlesTest {

    @Test
    void anotherWindowGoingAwayLeavesTheGamesWindowAlone() throws Exception {
        Field loaded = GLFW.class.getDeclaredField("nativeLoaded");
        loaded.setAccessible(true);
        loaded.setBoolean(null, true); // glfwCreateWindow would load libcofre otherwise
        try {
            assertEquals(0L, GLFW.glfwGetCurrentContext(), "no GL context without glfwMakeContextCurrent");

            long loadingScreen = GLFW.glfwCreateWindow(854, 480, "Loading", 0, 0);
            long game = GLFW.glfwCreateWindow(854, 480, "Minecraft", 0, 0);
            assertNotEquals(0L, loadingScreen);
            assertNotEquals(loadingScreen, game);
            assertEquals(game, GLFW.currentWindow, "events and callbacks belong to the newest window");

            Callbacks.glfwFreeCallbacks(loadingScreen);
            GLFW.glfwDestroyWindow(loadingScreen);
            assertEquals(game, GLFW.currentWindow, "the loading screen going away leaves the game's window and callbacks");

            GLFW.glfwMakeContextCurrent(game);
            assertEquals(game, GLFW.glfwGetCurrentContext());
            GLFW.glfwMakeContextCurrent(0);

            GLFW.glfwDestroyWindow(game);
            assertEquals(0L, GLFW.currentWindow);
        } finally {
            loaded.setBoolean(null, false);
        }
    }
}
