/*
 * Cofre's GLFW for Android. Replaces LWJGL 3.4.1's org.lwjgl.glfw.GLFW on the game classpath.
 * There is no native GLFW: the window is the :game activity's SurfaceView, and input arrives
 * through libcofre.so, which both ART and HotSpot load (so its event queue is shared).
 * Constants and signatures follow LWJGL (BSD-3-Clause, see NOTICE); the idea of a Java-side
 * GLFW replacement comes from PojavLauncher's jre_lwjgl3glfw (LGPL-3.0).
 */
package org.lwjgl.glfw;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.DoubleBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import org.lwjgl.BufferUtils;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.Library;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.system.SharedLibrary;

public class GLFW {

    // ---- Constants: LWJGL 3.4.1 org.lwjgl.glfw.GLFW, generated with javap -constants ----

    public static final int GLFW_VERSION_MAJOR = 3;
    public static final int GLFW_VERSION_MINOR = 4;
    public static final int GLFW_VERSION_REVISION = 0;
    public static final int GLFW_TRUE = 1;
    public static final int GLFW_FALSE = 0;
    public static final int GLFW_RELEASE = 0;
    public static final int GLFW_PRESS = 1;
    public static final int GLFW_REPEAT = 2;
    public static final int GLFW_HAT_CENTERED = 0;
    public static final int GLFW_HAT_UP = 1;
    public static final int GLFW_HAT_RIGHT = 2;
    public static final int GLFW_HAT_DOWN = 4;
    public static final int GLFW_HAT_LEFT = 8;
    public static final int GLFW_HAT_RIGHT_UP = 3;
    public static final int GLFW_HAT_RIGHT_DOWN = 6;
    public static final int GLFW_HAT_LEFT_UP = 9;
    public static final int GLFW_HAT_LEFT_DOWN = 12;
    public static final int GLFW_KEY_UNKNOWN = -1;
    public static final int GLFW_KEY_SPACE = 32;
    public static final int GLFW_KEY_APOSTROPHE = 39;
    public static final int GLFW_KEY_COMMA = 44;
    public static final int GLFW_KEY_MINUS = 45;
    public static final int GLFW_KEY_PERIOD = 46;
    public static final int GLFW_KEY_SLASH = 47;
    public static final int GLFW_KEY_0 = 48;
    public static final int GLFW_KEY_1 = 49;
    public static final int GLFW_KEY_2 = 50;
    public static final int GLFW_KEY_3 = 51;
    public static final int GLFW_KEY_4 = 52;
    public static final int GLFW_KEY_5 = 53;
    public static final int GLFW_KEY_6 = 54;
    public static final int GLFW_KEY_7 = 55;
    public static final int GLFW_KEY_8 = 56;
    public static final int GLFW_KEY_9 = 57;
    public static final int GLFW_KEY_SEMICOLON = 59;
    public static final int GLFW_KEY_EQUAL = 61;
    public static final int GLFW_KEY_A = 65;
    public static final int GLFW_KEY_B = 66;
    public static final int GLFW_KEY_C = 67;
    public static final int GLFW_KEY_D = 68;
    public static final int GLFW_KEY_E = 69;
    public static final int GLFW_KEY_F = 70;
    public static final int GLFW_KEY_G = 71;
    public static final int GLFW_KEY_H = 72;
    public static final int GLFW_KEY_I = 73;
    public static final int GLFW_KEY_J = 74;
    public static final int GLFW_KEY_K = 75;
    public static final int GLFW_KEY_L = 76;
    public static final int GLFW_KEY_M = 77;
    public static final int GLFW_KEY_N = 78;
    public static final int GLFW_KEY_O = 79;
    public static final int GLFW_KEY_P = 80;
    public static final int GLFW_KEY_Q = 81;
    public static final int GLFW_KEY_R = 82;
    public static final int GLFW_KEY_S = 83;
    public static final int GLFW_KEY_T = 84;
    public static final int GLFW_KEY_U = 85;
    public static final int GLFW_KEY_V = 86;
    public static final int GLFW_KEY_W = 87;
    public static final int GLFW_KEY_X = 88;
    public static final int GLFW_KEY_Y = 89;
    public static final int GLFW_KEY_Z = 90;
    public static final int GLFW_KEY_LEFT_BRACKET = 91;
    public static final int GLFW_KEY_BACKSLASH = 92;
    public static final int GLFW_KEY_RIGHT_BRACKET = 93;
    public static final int GLFW_KEY_GRAVE_ACCENT = 96;
    public static final int GLFW_KEY_WORLD_1 = 161;
    public static final int GLFW_KEY_WORLD_2 = 162;
    public static final int GLFW_KEY_ESCAPE = 256;
    public static final int GLFW_KEY_ENTER = 257;
    public static final int GLFW_KEY_TAB = 258;
    public static final int GLFW_KEY_BACKSPACE = 259;
    public static final int GLFW_KEY_INSERT = 260;
    public static final int GLFW_KEY_DELETE = 261;
    public static final int GLFW_KEY_RIGHT = 262;
    public static final int GLFW_KEY_LEFT = 263;
    public static final int GLFW_KEY_DOWN = 264;
    public static final int GLFW_KEY_UP = 265;
    public static final int GLFW_KEY_PAGE_UP = 266;
    public static final int GLFW_KEY_PAGE_DOWN = 267;
    public static final int GLFW_KEY_HOME = 268;
    public static final int GLFW_KEY_END = 269;
    public static final int GLFW_KEY_CAPS_LOCK = 280;
    public static final int GLFW_KEY_SCROLL_LOCK = 281;
    public static final int GLFW_KEY_NUM_LOCK = 282;
    public static final int GLFW_KEY_PRINT_SCREEN = 283;
    public static final int GLFW_KEY_PAUSE = 284;
    public static final int GLFW_KEY_F1 = 290;
    public static final int GLFW_KEY_F2 = 291;
    public static final int GLFW_KEY_F3 = 292;
    public static final int GLFW_KEY_F4 = 293;
    public static final int GLFW_KEY_F5 = 294;
    public static final int GLFW_KEY_F6 = 295;
    public static final int GLFW_KEY_F7 = 296;
    public static final int GLFW_KEY_F8 = 297;
    public static final int GLFW_KEY_F9 = 298;
    public static final int GLFW_KEY_F10 = 299;
    public static final int GLFW_KEY_F11 = 300;
    public static final int GLFW_KEY_F12 = 301;
    public static final int GLFW_KEY_F13 = 302;
    public static final int GLFW_KEY_F14 = 303;
    public static final int GLFW_KEY_F15 = 304;
    public static final int GLFW_KEY_F16 = 305;
    public static final int GLFW_KEY_F17 = 306;
    public static final int GLFW_KEY_F18 = 307;
    public static final int GLFW_KEY_F19 = 308;
    public static final int GLFW_KEY_F20 = 309;
    public static final int GLFW_KEY_F21 = 310;
    public static final int GLFW_KEY_F22 = 311;
    public static final int GLFW_KEY_F23 = 312;
    public static final int GLFW_KEY_F24 = 313;
    public static final int GLFW_KEY_F25 = 314;
    public static final int GLFW_KEY_KP_0 = 320;
    public static final int GLFW_KEY_KP_1 = 321;
    public static final int GLFW_KEY_KP_2 = 322;
    public static final int GLFW_KEY_KP_3 = 323;
    public static final int GLFW_KEY_KP_4 = 324;
    public static final int GLFW_KEY_KP_5 = 325;
    public static final int GLFW_KEY_KP_6 = 326;
    public static final int GLFW_KEY_KP_7 = 327;
    public static final int GLFW_KEY_KP_8 = 328;
    public static final int GLFW_KEY_KP_9 = 329;
    public static final int GLFW_KEY_KP_DECIMAL = 330;
    public static final int GLFW_KEY_KP_DIVIDE = 331;
    public static final int GLFW_KEY_KP_MULTIPLY = 332;
    public static final int GLFW_KEY_KP_SUBTRACT = 333;
    public static final int GLFW_KEY_KP_ADD = 334;
    public static final int GLFW_KEY_KP_ENTER = 335;
    public static final int GLFW_KEY_KP_EQUAL = 336;
    public static final int GLFW_KEY_LEFT_SHIFT = 340;
    public static final int GLFW_KEY_LEFT_CONTROL = 341;
    public static final int GLFW_KEY_LEFT_ALT = 342;
    public static final int GLFW_KEY_LEFT_SUPER = 343;
    public static final int GLFW_KEY_RIGHT_SHIFT = 344;
    public static final int GLFW_KEY_RIGHT_CONTROL = 345;
    public static final int GLFW_KEY_RIGHT_ALT = 346;
    public static final int GLFW_KEY_RIGHT_SUPER = 347;
    public static final int GLFW_KEY_MENU = 348;
    public static final int GLFW_KEY_LAST = 348;
    public static final int GLFW_MOD_SHIFT = 1;
    public static final int GLFW_MOD_CONTROL = 2;
    public static final int GLFW_MOD_ALT = 4;
    public static final int GLFW_MOD_SUPER = 8;
    public static final int GLFW_MOD_CAPS_LOCK = 16;
    public static final int GLFW_MOD_NUM_LOCK = 32;
    public static final int GLFW_MOUSE_BUTTON_1 = 0;
    public static final int GLFW_MOUSE_BUTTON_2 = 1;
    public static final int GLFW_MOUSE_BUTTON_3 = 2;
    public static final int GLFW_MOUSE_BUTTON_4 = 3;
    public static final int GLFW_MOUSE_BUTTON_5 = 4;
    public static final int GLFW_MOUSE_BUTTON_6 = 5;
    public static final int GLFW_MOUSE_BUTTON_7 = 6;
    public static final int GLFW_MOUSE_BUTTON_8 = 7;
    public static final int GLFW_MOUSE_BUTTON_LAST = 7;
    public static final int GLFW_MOUSE_BUTTON_LEFT = 0;
    public static final int GLFW_MOUSE_BUTTON_RIGHT = 1;
    public static final int GLFW_MOUSE_BUTTON_MIDDLE = 2;
    public static final int GLFW_JOYSTICK_1 = 0;
    public static final int GLFW_JOYSTICK_2 = 1;
    public static final int GLFW_JOYSTICK_3 = 2;
    public static final int GLFW_JOYSTICK_4 = 3;
    public static final int GLFW_JOYSTICK_5 = 4;
    public static final int GLFW_JOYSTICK_6 = 5;
    public static final int GLFW_JOYSTICK_7 = 6;
    public static final int GLFW_JOYSTICK_8 = 7;
    public static final int GLFW_JOYSTICK_9 = 8;
    public static final int GLFW_JOYSTICK_10 = 9;
    public static final int GLFW_JOYSTICK_11 = 10;
    public static final int GLFW_JOYSTICK_12 = 11;
    public static final int GLFW_JOYSTICK_13 = 12;
    public static final int GLFW_JOYSTICK_14 = 13;
    public static final int GLFW_JOYSTICK_15 = 14;
    public static final int GLFW_JOYSTICK_16 = 15;
    public static final int GLFW_JOYSTICK_LAST = 15;
    public static final int GLFW_GAMEPAD_BUTTON_A = 0;
    public static final int GLFW_GAMEPAD_BUTTON_B = 1;
    public static final int GLFW_GAMEPAD_BUTTON_X = 2;
    public static final int GLFW_GAMEPAD_BUTTON_Y = 3;
    public static final int GLFW_GAMEPAD_BUTTON_LEFT_BUMPER = 4;
    public static final int GLFW_GAMEPAD_BUTTON_RIGHT_BUMPER = 5;
    public static final int GLFW_GAMEPAD_BUTTON_BACK = 6;
    public static final int GLFW_GAMEPAD_BUTTON_START = 7;
    public static final int GLFW_GAMEPAD_BUTTON_GUIDE = 8;
    public static final int GLFW_GAMEPAD_BUTTON_LEFT_THUMB = 9;
    public static final int GLFW_GAMEPAD_BUTTON_RIGHT_THUMB = 10;
    public static final int GLFW_GAMEPAD_BUTTON_DPAD_UP = 11;
    public static final int GLFW_GAMEPAD_BUTTON_DPAD_RIGHT = 12;
    public static final int GLFW_GAMEPAD_BUTTON_DPAD_DOWN = 13;
    public static final int GLFW_GAMEPAD_BUTTON_DPAD_LEFT = 14;
    public static final int GLFW_GAMEPAD_BUTTON_LAST = 14;
    public static final int GLFW_GAMEPAD_BUTTON_CROSS = 0;
    public static final int GLFW_GAMEPAD_BUTTON_CIRCLE = 1;
    public static final int GLFW_GAMEPAD_BUTTON_SQUARE = 2;
    public static final int GLFW_GAMEPAD_BUTTON_TRIANGLE = 3;
    public static final int GLFW_GAMEPAD_AXIS_LEFT_X = 0;
    public static final int GLFW_GAMEPAD_AXIS_LEFT_Y = 1;
    public static final int GLFW_GAMEPAD_AXIS_RIGHT_X = 2;
    public static final int GLFW_GAMEPAD_AXIS_RIGHT_Y = 3;
    public static final int GLFW_GAMEPAD_AXIS_LEFT_TRIGGER = 4;
    public static final int GLFW_GAMEPAD_AXIS_RIGHT_TRIGGER = 5;
    public static final int GLFW_GAMEPAD_AXIS_LAST = 5;
    public static final int GLFW_NO_ERROR = 0;
    public static final int GLFW_NOT_INITIALIZED = 0x10001;
    public static final int GLFW_NO_CURRENT_CONTEXT = 0x10002;
    public static final int GLFW_INVALID_ENUM = 0x10003;
    public static final int GLFW_INVALID_VALUE = 0x10004;
    public static final int GLFW_OUT_OF_MEMORY = 0x10005;
    public static final int GLFW_API_UNAVAILABLE = 0x10006;
    public static final int GLFW_VERSION_UNAVAILABLE = 0x10007;
    public static final int GLFW_PLATFORM_ERROR = 0x10008;
    public static final int GLFW_FORMAT_UNAVAILABLE = 0x10009;
    public static final int GLFW_NO_WINDOW_CONTEXT = 0x1000A;
    public static final int GLFW_CURSOR_UNAVAILABLE = 0x1000B;
    public static final int GLFW_FEATURE_UNAVAILABLE = 0x1000C;
    public static final int GLFW_FEATURE_UNIMPLEMENTED = 0x1000D;
    public static final int GLFW_PLATFORM_UNAVAILABLE = 0x1000E;
    public static final int GLFW_FOCUSED = 0x20001;
    public static final int GLFW_ICONIFIED = 0x20002;
    public static final int GLFW_RESIZABLE = 0x20003;
    public static final int GLFW_VISIBLE = 0x20004;
    public static final int GLFW_DECORATED = 0x20005;
    public static final int GLFW_AUTO_ICONIFY = 0x20006;
    public static final int GLFW_FLOATING = 0x20007;
    public static final int GLFW_MAXIMIZED = 0x20008;
    public static final int GLFW_CENTER_CURSOR = 0x20009;
    public static final int GLFW_TRANSPARENT_FRAMEBUFFER = 0x2000A;
    public static final int GLFW_HOVERED = 0x2000B;
    public static final int GLFW_FOCUS_ON_SHOW = 0x2000C;
    public static final int GLFW_MOUSE_PASSTHROUGH = 0x2000D;
    public static final int GLFW_POSITION_X = 0x2000E;
    public static final int GLFW_POSITION_Y = 0x2000F;
    public static final int GLFW_SOFT_FULLSCREEN = 0x20010;
    public static final int GLFW_CURSOR = 0x33001;
    public static final int GLFW_STICKY_KEYS = 0x33002;
    public static final int GLFW_STICKY_MOUSE_BUTTONS = 0x33003;
    public static final int GLFW_LOCK_KEY_MODS = 0x33004;
    public static final int GLFW_RAW_MOUSE_MOTION = 0x33005;
    public static final int GLFW_UNLIMITED_MOUSE_BUTTONS = 0x33006;
    public static final int GLFW_IME = 0x33007;
    public static final int GLFW_CURSOR_NORMAL = 0x34001;
    public static final int GLFW_CURSOR_HIDDEN = 0x34002;
    public static final int GLFW_CURSOR_DISABLED = 0x34003;
    public static final int GLFW_CURSOR_CAPTURED = 0x34004;
    public static final int GLFW_ARROW_CURSOR = 0x36001;
    public static final int GLFW_IBEAM_CURSOR = 0x36002;
    public static final int GLFW_CROSSHAIR_CURSOR = 0x36003;
    public static final int GLFW_POINTING_HAND_CURSOR = 0x36004;
    public static final int GLFW_RESIZE_EW_CURSOR = 0x36005;
    public static final int GLFW_RESIZE_NS_CURSOR = 0x36006;
    public static final int GLFW_RESIZE_NWSE_CURSOR = 0x36007;
    public static final int GLFW_RESIZE_NESW_CURSOR = 0x36008;
    public static final int GLFW_RESIZE_ALL_CURSOR = 0x36009;
    public static final int GLFW_NOT_ALLOWED_CURSOR = 0x3600A;
    public static final int GLFW_HRESIZE_CURSOR = 0x36005;
    public static final int GLFW_VRESIZE_CURSOR = 0x36006;
    public static final int GLFW_HAND_CURSOR = 0x36004;
    public static final int GLFW_CONNECTED = 0x40001;
    public static final int GLFW_DISCONNECTED = 0x40002;
    public static final int GLFW_JOYSTICK_HAT_BUTTONS = 0x50001;
    public static final int GLFW_ANGLE_PLATFORM_TYPE = 0x50002;
    public static final int GLFW_ANY_POSITION = -2147483648;
    public static final int GLFW_PLATFORM = 0x50003;
    public static final int GLFW_MANAGE_PREEDIT_CANDIDATE = 0x50004;
    public static final int GLFW_COCOA_CHDIR_RESOURCES = 0x51001;
    public static final int GLFW_COCOA_MENUBAR = 0x51002;
    public static final int GLFW_X11_XCB_VULKAN_SURFACE = 0x52001;
    public static final int GLFW_X11_ONTHESPOT = 0x52002;
    public static final int GLFW_WAYLAND_LIBDECOR = 0x53001;
    public static final int GLFW_ANY_PLATFORM = 0x60000;
    public static final int GLFW_PLATFORM_WIN32 = 0x60001;
    public static final int GLFW_PLATFORM_COCOA = 0x60002;
    public static final int GLFW_PLATFORM_WAYLAND = 0x60003;
    public static final int GLFW_PLATFORM_X11 = 0x60004;
    public static final int GLFW_PLATFORM_NULL = 0x60005;
    public static final int GLFW_DONT_CARE = -1;
    public static final int GLFW_RED_BITS = 0x21001;
    public static final int GLFW_GREEN_BITS = 0x21002;
    public static final int GLFW_BLUE_BITS = 0x21003;
    public static final int GLFW_ALPHA_BITS = 0x21004;
    public static final int GLFW_DEPTH_BITS = 0x21005;
    public static final int GLFW_STENCIL_BITS = 0x21006;
    public static final int GLFW_ACCUM_RED_BITS = 0x21007;
    public static final int GLFW_ACCUM_GREEN_BITS = 0x21008;
    public static final int GLFW_ACCUM_BLUE_BITS = 0x21009;
    public static final int GLFW_ACCUM_ALPHA_BITS = 0x2100A;
    public static final int GLFW_AUX_BUFFERS = 0x2100B;
    public static final int GLFW_STEREO = 0x2100C;
    public static final int GLFW_SAMPLES = 0x2100D;
    public static final int GLFW_SRGB_CAPABLE = 0x2100E;
    public static final int GLFW_REFRESH_RATE = 0x2100F;
    public static final int GLFW_DOUBLEBUFFER = 0x21010;
    public static final int GLFW_CLIENT_API = 0x22001;
    public static final int GLFW_CONTEXT_VERSION_MAJOR = 0x22002;
    public static final int GLFW_CONTEXT_VERSION_MINOR = 0x22003;
    public static final int GLFW_CONTEXT_REVISION = 0x22004;
    public static final int GLFW_CONTEXT_ROBUSTNESS = 0x22005;
    public static final int GLFW_OPENGL_FORWARD_COMPAT = 0x22006;
    public static final int GLFW_CONTEXT_DEBUG = 0x22007;
    public static final int GLFW_OPENGL_DEBUG_CONTEXT = 0x22007;
    public static final int GLFW_OPENGL_PROFILE = 0x22008;
    public static final int GLFW_CONTEXT_RELEASE_BEHAVIOR = 0x22009;
    public static final int GLFW_CONTEXT_NO_ERROR = 0x2200A;
    public static final int GLFW_CONTEXT_CREATION_API = 0x2200B;
    public static final int GLFW_SCALE_TO_MONITOR = 0x2200C;
    public static final int GLFW_SCALE_FRAMEBUFFER = 0x2200D;
    public static final int GLFW_COCOA_RETINA_FRAMEBUFFER = 0x23001;
    public static final int GLFW_COCOA_FRAME_NAME = 0x23002;
    public static final int GLFW_COCOA_GRAPHICS_SWITCHING = 0x23003;
    public static final int GLFW_X11_CLASS_NAME = 0x24001;
    public static final int GLFW_X11_INSTANCE_NAME = 0x24002;
    public static final int GLFW_WIN32_KEYBOARD_MENU = 0x25001;
    public static final int GLFW_WIN32_SHOWDEFAULT = 0x25002;
    public static final int GLFW_WAYLAND_APP_ID = 0x26001;
    public static final int GLFW_NO_API = 0;
    public static final int GLFW_OPENGL_API = 0x30001;
    public static final int GLFW_OPENGL_ES_API = 0x30002;
    public static final int GLFW_NO_ROBUSTNESS = 0;
    public static final int GLFW_NO_RESET_NOTIFICATION = 0x31001;
    public static final int GLFW_LOSE_CONTEXT_ON_RESET = 0x31002;
    public static final int GLFW_OPENGL_ANY_PROFILE = 0;
    public static final int GLFW_OPENGL_CORE_PROFILE = 0x32001;
    public static final int GLFW_OPENGL_COMPAT_PROFILE = 0x32002;
    public static final int GLFW_ANY_RELEASE_BEHAVIOR = 0;
    public static final int GLFW_RELEASE_BEHAVIOR_FLUSH = 0x35001;
    public static final int GLFW_RELEASE_BEHAVIOR_NONE = 0x35002;
    public static final int GLFW_NATIVE_CONTEXT_API = 0x36001;
    public static final int GLFW_EGL_CONTEXT_API = 0x36002;
    public static final int GLFW_OSMESA_CONTEXT_API = 0x36003;
    public static final int GLFW_ANGLE_PLATFORM_TYPE_NONE = 0x37001;
    public static final int GLFW_ANGLE_PLATFORM_TYPE_OPENGL = 0x37002;
    public static final int GLFW_ANGLE_PLATFORM_TYPE_OPENGLES = 0x37003;
    public static final int GLFW_ANGLE_PLATFORM_TYPE_D3D9 = 0x37004;
    public static final int GLFW_ANGLE_PLATFORM_TYPE_D3D11 = 0x37005;
    public static final int GLFW_ANGLE_PLATFORM_TYPE_VULKAN = 0x37007;
    public static final int GLFW_ANGLE_PLATFORM_TYPE_METAL = 0x37008;
    public static final int GLFW_WAYLAND_PREFER_LIBDECOR = 0x38001;
    public static final int GLFW_WAYLAND_DISABLE_LIBDECOR = 0x38002;

    // ---- Cofre state ------------------------------------------------------------------------

    /** The only window and monitor. Handles just need to be non-NULL. */
    static final long WINDOW = 1L, MONITOR = 2L;

    // Event records written by libcofre (keep in sync with cofre.c and GameActivity.kt).
    static final int EV_KEY = 1, EV_CHAR = 2, EV_MOUSE_BUTTON = 3, EV_CURSOR_POS = 4, EV_CURSOR_MOVE = 5,
        EV_SCROLL = 6, EV_WINDOW_SIZE = 7, EV_FOCUS = 8, EV_ICONIFY = 9, EV_CLOSE = 10;
    static final int EVENT_SIZE = 40, EVENT_CAPACITY = 256;

    private static final ByteBuffer EVENTS = ByteBuffer.allocateDirect(EVENT_SIZE * EVENT_CAPACITY).order(ByteOrder.nativeOrder());
    private static final byte[] KEYS = new byte[GLFW_KEY_LAST + 1];
    private static final byte[] BUTTONS = new byte[GLFW_MOUSE_BUTTON_LAST + 1];
    private static final Map<Integer, Integer> HINTS = new HashMap<>();
    private static final Map<Integer, Integer> INPUT_MODES = new HashMap<>();

    private static boolean nativeLoaded, pumping, shouldClose, focused = true, iconified;
    private static int width = 1280, height = 720;
    private static double cursorX, cursorY;
    private static long startNanos = System.nanoTime(), windowMonitor, userPointer, currentContext;
    private static String clipboard = "";
    private static SharedLibrary library;
    private static GLFWVidMode videoMode;
    private static PointerBuffer monitors;

    private static GLFWErrorCallbackI errorCb;
    private static GLFWMonitorCallbackI monitorCb;
    private static GLFWJoystickCallbackI joystickCb;
    private static GLFWKeyCallbackI keyCb;
    private static GLFWCharCallbackI charCb;
    private static GLFWCharModsCallbackI charModsCb;
    private static GLFWMouseButtonCallbackI mouseButtonCb;
    private static GLFWCursorPosCallbackI cursorPosCb;
    private static GLFWCursorEnterCallbackI cursorEnterCb;
    private static GLFWScrollCallbackI scrollCb;
    private static GLFWDropCallbackI dropCb;
    private static GLFWWindowPosCallbackI windowPosCb;
    private static GLFWWindowSizeCallbackI windowSizeCb;
    private static GLFWFramebufferSizeCallbackI framebufferSizeCb;
    private static GLFWWindowCloseCallbackI windowCloseCb;
    private static GLFWWindowRefreshCallbackI windowRefreshCb;
    private static GLFWWindowFocusCallbackI windowFocusCb;
    private static GLFWWindowIconifyCallbackI windowIconifyCb;
    private static GLFWWindowMaximizeCallbackI windowMaximizeCb;
    private static GLFWWindowContentScaleCallbackI windowContentScaleCb;
    private static GLFWIMEStatusCallbackI imeStatusCb;
    private static GLFWPreeditCallbackI preeditCb;
    private static GLFWPreeditCandidateCallbackI preeditCandidateCb;

    protected GLFW() {
        throw new UnsupportedOperationException();
    }

    /** Path of libcofre.so: the launcher passes our native directory as {@code org.lwjgl.librarypath}. */
    static String libcofrePath() {
        return System.getProperty("cofre.libcofre", System.getProperty("org.lwjgl.librarypath", ".") + "/libcofre.so");
    }

    static synchronized void loadNative() {
        if (nativeLoaded) return;
        System.load(libcofrePath());
        nativeLoaded = true;
    }

    // Implemented in runtime/src/main/cpp/cofre.c.
    private static native int nPollEvents(ByteBuffer out, int capacity);
    private static native long nWindowSize();
    private static native void nSetCursorCaptured(boolean captured);
    static native long nAcquireWindow();
    static native void nReleaseWindow(long window);

    private static <T> T previous(Object callback, Class<T> type) {
        return type.isInstance(callback) ? type.cast(callback) : null;
    }

    // ---- Init, errors, version, time ---------------------------------------------------------

    public static SharedLibrary getLibrary() {
        if (library == null) library = Library.loadNative(GLFW.class, "org.lwjgl.glfw", libcofrePath());
        return library;
    }

    public static boolean glfwInit() {
        loadNative();
        long size = nWindowSize();
        if (size != 0) {
            width = (int) (size >>> 32);
            height = (int) size;
        }
        startNanos = System.nanoTime();
        return true;
    }

    public static void glfwTerminate() {}
    public static void glfwInitHint(int hint, int value) {}
    public static void glfwInitAllocator(GLFWAllocator allocator) {}
    public static void glfwGetVersion(int[] major, int[] minor, int[] rev) {
        if (major != null) major[0] = GLFW_VERSION_MAJOR;
        if (minor != null) minor[0] = GLFW_VERSION_MINOR;
        if (rev != null) rev[0] = GLFW_VERSION_REVISION;
    }
    public static void glfwGetVersion(IntBuffer major, IntBuffer minor, IntBuffer rev) {
        if (major != null) major.put(major.position(), GLFW_VERSION_MAJOR);
        if (minor != null) minor.put(minor.position(), GLFW_VERSION_MINOR);
        if (rev != null) rev.put(rev.position(), GLFW_VERSION_REVISION);
    }
    public static String glfwGetVersionString() {
        return "3.4.0 Cofre Android";
    }

    public static int glfwGetError(PointerBuffer description) {
        if (description != null) description.put(description.position(), 0L);
        return GLFW_NO_ERROR;
    }

    public static GLFWErrorCallback glfwSetErrorCallback(GLFWErrorCallbackI cb) {
        Object prev = errorCb;
        errorCb = cb;
        return previous(prev, GLFWErrorCallback.class);
    }

    public static int glfwGetPlatform() {
        return GLFW_PLATFORM_NULL;
    }
    public static boolean glfwPlatformSupported(int platform) {
        return platform == GLFW_PLATFORM_NULL;
    }

    public static double glfwGetTime() {
        return (System.nanoTime() - startNanos) / 1e9;
    }
    public static void glfwSetTime(double time) {
        startNanos = System.nanoTime() - (long) (time * 1e9);
    }
    public static long glfwGetTimerValue() {
        return System.nanoTime();
    }
    public static long glfwGetTimerFrequency() {
        return 1_000_000_000L;
    }

    // ---- Monitors ----------------------------------------------------------------------------

    public static PointerBuffer glfwGetMonitors() {
        if (monitors == null) monitors = BufferUtils.createPointerBuffer(1).put(0, MONITOR);
        return monitors;
    }
    public static long glfwGetPrimaryMonitor() {
        return MONITOR;
    }
    public static void glfwGetMonitorPos(long monitor, int[] x, int[] y) {
        if (x != null) x[0] = 0;
        if (y != null) y[0] = 0;
    }
    public static void glfwGetMonitorPos(long monitor, IntBuffer x, IntBuffer y) {
        if (x != null) x.put(x.position(), 0);
        if (y != null) y.put(y.position(), 0);
    }
    public static void glfwGetMonitorWorkarea(long monitor, int[] x, int[] y, int[] w, int[] h) {
        if (x != null) x[0] = 0;
        if (y != null) y[0] = 0;
        if (w != null) w[0] = width;
        if (h != null) h[0] = height;
    }
    public static void glfwGetMonitorWorkarea(long monitor, IntBuffer x, IntBuffer y, IntBuffer w, IntBuffer h) {
        if (x != null) x.put(x.position(), 0);
        if (y != null) y.put(y.position(), 0);
        if (w != null) w.put(w.position(), width);
        if (h != null) h.put(h.position(), height);
    }
    public static void glfwGetMonitorPhysicalSize(long monitor, int[] w, int[] h) {
        if (w != null) w[0] = 0;
        if (h != null) h[0] = 0;
    }
    public static void glfwGetMonitorPhysicalSize(long monitor, IntBuffer w, IntBuffer h) {
        if (w != null) w.put(w.position(), 0);
        if (h != null) h.put(h.position(), 0);
    }
    public static void glfwGetMonitorContentScale(long monitor, float[] x, float[] y) {
        if (x != null) x[0] = 1f;
        if (y != null) y[0] = 1f;
    }
    public static void glfwGetMonitorContentScale(long monitor, FloatBuffer x, FloatBuffer y) {
        if (x != null) x.put(x.position(), 1f);
        if (y != null) y.put(y.position(), 1f);
    }
    public static String glfwGetMonitorName(long monitor) {
        return "Android";
    }
    public static void glfwSetMonitorUserPointer(long monitor, long pointer) {}
    public static long glfwGetMonitorUserPointer(long monitor) {
        return 0L;
    }
    public static GLFWMonitorCallback glfwSetMonitorCallback(GLFWMonitorCallbackI cb) {
        Object prev = monitorCb;
        monitorCb = cb;
        return previous(prev, GLFWMonitorCallback.class);
    }

    public static GLFWVidMode glfwGetVideoMode(long monitor) {
        if (videoMode == null) videoMode = new GLFWVidMode(BufferUtils.createByteBuffer(GLFWVidMode.SIZEOF));
        long address = videoMode.address();
        MemoryUtil.memPutInt(address + GLFWVidMode.WIDTH, width);
        MemoryUtil.memPutInt(address + GLFWVidMode.HEIGHT, height);
        MemoryUtil.memPutInt(address + GLFWVidMode.REDBITS, 8);
        MemoryUtil.memPutInt(address + GLFWVidMode.GREENBITS, 8);
        MemoryUtil.memPutInt(address + GLFWVidMode.BLUEBITS, 8);
        MemoryUtil.memPutInt(address + GLFWVidMode.REFRESHRATE, Integer.getInteger("cofre.refreshRate", 60));
        return videoMode;
    }
    public static GLFWVidMode.Buffer glfwGetVideoModes(long monitor) {
        return new GLFWVidMode.Buffer(MemoryUtil.memByteBuffer(glfwGetVideoMode(monitor).address(), GLFWVidMode.SIZEOF));
    }

    public static void glfwSetGamma(long monitor, float gamma) {}
    public static GLFWGammaRamp glfwGetGammaRamp(long monitor) {
        return null;
    }
    public static void glfwSetGammaRamp(long monitor, GLFWGammaRamp ramp) {}

    // ---- Window ------------------------------------------------------------------------------

    public static void glfwDefaultWindowHints() {
        HINTS.clear();
    }
    public static void glfwWindowHint(int hint, int value) {
        HINTS.put(hint, value);
    }
    public static void glfwWindowHintString(int hint, CharSequence value) {}
    public static void glfwWindowHintString(int hint, ByteBuffer value) {}

    public static long glfwCreateWindow(int w, int h, CharSequence title, long monitor, long share) {
        if (!nativeLoaded) glfwInit();
        windowMonitor = monitor;
        shouldClose = false;
        return WINDOW;
    }
    public static long glfwCreateWindow(int w, int h, ByteBuffer title, long monitor, long share) {
        return glfwCreateWindow(w, h, (CharSequence) null, monitor, share);
    }
    public static void glfwDestroyWindow(long window) {
        if (window != 0) Callbacks.glfwFreeCallbacks(window);
    }

    public static boolean glfwWindowShouldClose(long window) {
        return shouldClose;
    }
    public static void glfwSetWindowShouldClose(long window, boolean value) {
        shouldClose = value;
    }
    public static String glfwGetWindowTitle(long window) {
        return "Minecraft";
    }
    public static void glfwSetWindowTitle(long window, CharSequence title) {}
    public static void glfwSetWindowTitle(long window, ByteBuffer title) {}
    public static void glfwSetWindowIcon(long window, GLFWImage.Buffer images) {}

    public static void glfwGetWindowPos(long window, int[] x, int[] y) {
        glfwGetMonitorPos(MONITOR, x, y);
    }
    public static void glfwGetWindowPos(long window, IntBuffer x, IntBuffer y) {
        glfwGetMonitorPos(MONITOR, x, y);
    }
    public static void glfwSetWindowPos(long window, int x, int y) {}

    public static void glfwGetWindowSize(long window, int[] w, int[] h) {
        if (w != null) w[0] = width;
        if (h != null) h[0] = height;
    }
    public static void glfwGetWindowSize(long window, IntBuffer w, IntBuffer h) {
        if (w != null) w.put(w.position(), width);
        if (h != null) h.put(h.position(), height);
    }
    public static void glfwGetFramebufferSize(long window, int[] w, int[] h) {
        glfwGetWindowSize(window, w, h);
    }
    public static void glfwGetFramebufferSize(long window, IntBuffer w, IntBuffer h) {
        glfwGetWindowSize(window, w, h);
    }
    public static void glfwGetWindowFrameSize(long window, int[] l, int[] t, int[] r, int[] b) {
        if (l != null) l[0] = 0;
        if (t != null) t[0] = 0;
        if (r != null) r[0] = 0;
        if (b != null) b[0] = 0;
    }
    public static void glfwGetWindowFrameSize(long window, IntBuffer l, IntBuffer t, IntBuffer r, IntBuffer b) {
        if (l != null) l.put(l.position(), 0);
        if (t != null) t.put(t.position(), 0);
        if (r != null) r.put(r.position(), 0);
        if (b != null) b.put(b.position(), 0);
    }
    public static void glfwGetWindowContentScale(long window, float[] x, float[] y) {
        glfwGetMonitorContentScale(MONITOR, x, y);
    }
    public static void glfwGetWindowContentScale(long window, FloatBuffer x, FloatBuffer y) {
        glfwGetMonitorContentScale(MONITOR, x, y);
    }
    // The surface decides the size; these are requests GLFW may ignore too.
    public static void glfwSetWindowSize(long window, int w, int h) {}
    public static void glfwSetWindowSizeLimits(long window, int minW, int minH, int maxW, int maxH) {}
    public static void glfwSetWindowAspectRatio(long window, int numer, int denom) {}
    public static float glfwGetWindowOpacity(long window) {
        return 1f;
    }
    public static void glfwSetWindowOpacity(long window, float opacity) {}
    public static void glfwIconifyWindow(long window) {}
    public static void glfwRestoreWindow(long window) {}
    public static void glfwMaximizeWindow(long window) {}
    public static void glfwShowWindow(long window) {}
    public static void glfwHideWindow(long window) {}
    public static void glfwFocusWindow(long window) {}
    public static void glfwRequestWindowAttention(long window) {}

    public static long glfwGetWindowMonitor(long window) {
        return windowMonitor;
    }
    public static void glfwSetWindowMonitor(long window, long monitor, int x, int y, int w, int h, int refreshRate) {
        windowMonitor = monitor;
    }

    public static int glfwGetWindowAttrib(long window, int attrib) {
        switch (attrib) {
            case GLFW_FOCUSED: case GLFW_HOVERED: return focused ? GLFW_TRUE : GLFW_FALSE;
            case GLFW_ICONIFIED: return iconified ? GLFW_TRUE : GLFW_FALSE;
            case GLFW_VISIBLE: return iconified ? GLFW_FALSE : GLFW_TRUE;
            case GLFW_MAXIMIZED: case GLFW_DECORATED: case GLFW_RESIZABLE: return GLFW_TRUE;
            case GLFW_CLIENT_API: return HINTS.getOrDefault(GLFW_CLIENT_API, GLFW_NO_API);
            default: return HINTS.getOrDefault(attrib, GLFW_FALSE);
        }
    }
    public static void glfwSetWindowAttrib(long window, int attrib, int value) {
        HINTS.put(attrib, value);
    }
    public static void glfwSetWindowUserPointer(long window, long pointer) {
        userPointer = pointer;
    }
    public static long glfwGetWindowUserPointer(long window) {
        return userPointer;
    }

    // There's no GL here: Minecraft 26.2 runs on Vulkan. These keep GL-shaped callers harmless.
    public static void glfwMakeContextCurrent(long window) {
        currentContext = window;
    }
    public static long glfwGetCurrentContext() {
        return currentContext != 0 ? currentContext : WINDOW;
    }
    public static void glfwSwapBuffers(long window) {}
    public static void glfwSwapInterval(int interval) {}
    public static boolean glfwExtensionSupported(CharSequence extension) {
        return false;
    }
    public static boolean glfwExtensionSupported(ByteBuffer extension) {
        return false;
    }
    public static long glfwGetProcAddress(CharSequence name) {
        return 0L;
    }
    public static long glfwGetProcAddress(ByteBuffer name) {
        return 0L;
    }

    // ---- Window callbacks --------------------------------------------------------------------

    public static GLFWWindowPosCallback glfwSetWindowPosCallback(long window, GLFWWindowPosCallbackI cb) {
        Object prev = windowPosCb;
        windowPosCb = cb;
        return previous(prev, GLFWWindowPosCallback.class);
    }
    public static GLFWWindowSizeCallback glfwSetWindowSizeCallback(long window, GLFWWindowSizeCallbackI cb) {
        Object prev = windowSizeCb;
        windowSizeCb = cb;
        return previous(prev, GLFWWindowSizeCallback.class);
    }
    public static GLFWFramebufferSizeCallback glfwSetFramebufferSizeCallback(long window, GLFWFramebufferSizeCallbackI cb) {
        Object prev = framebufferSizeCb;
        framebufferSizeCb = cb;
        return previous(prev, GLFWFramebufferSizeCallback.class);
    }
    public static GLFWWindowCloseCallback glfwSetWindowCloseCallback(long window, GLFWWindowCloseCallbackI cb) {
        Object prev = windowCloseCb;
        windowCloseCb = cb;
        return previous(prev, GLFWWindowCloseCallback.class);
    }
    public static GLFWWindowRefreshCallback glfwSetWindowRefreshCallback(long window, GLFWWindowRefreshCallbackI cb) {
        Object prev = windowRefreshCb;
        windowRefreshCb = cb;
        return previous(prev, GLFWWindowRefreshCallback.class);
    }
    public static GLFWWindowFocusCallback glfwSetWindowFocusCallback(long window, GLFWWindowFocusCallbackI cb) {
        Object prev = windowFocusCb;
        windowFocusCb = cb;
        return previous(prev, GLFWWindowFocusCallback.class);
    }
    public static GLFWWindowIconifyCallback glfwSetWindowIconifyCallback(long window, GLFWWindowIconifyCallbackI cb) {
        Object prev = windowIconifyCb;
        windowIconifyCb = cb;
        return previous(prev, GLFWWindowIconifyCallback.class);
    }
    public static GLFWWindowMaximizeCallback glfwSetWindowMaximizeCallback(long window, GLFWWindowMaximizeCallbackI cb) {
        Object prev = windowMaximizeCb;
        windowMaximizeCb = cb;
        return previous(prev, GLFWWindowMaximizeCallback.class);
    }
    public static GLFWWindowContentScaleCallback glfwSetWindowContentScaleCallback(long window, GLFWWindowContentScaleCallbackI cb) {
        Object prev = windowContentScaleCb;
        windowContentScaleCb = cb;
        return previous(prev, GLFWWindowContentScaleCallback.class);
    }

    // ---- Events ------------------------------------------------------------------------------

    public static void glfwPollEvents() {
        if (!nativeLoaded || pumping) return; // a callback may poll again (Minecraft redraws during input)
        pumping = true;
        try {
            int n;
            do {
                n = nPollEvents(EVENTS, EVENT_CAPACITY);
                for (int i = 0; i < n; i++) dispatch(i * EVENT_SIZE);
            } while (n == EVENT_CAPACITY);
        } finally {
            pumping = false;
        }
    }
    public static void glfwWaitEvents() {
        glfwWaitEventsTimeout(0.005);
    }
    public static void glfwWaitEventsTimeout(double timeout) {
        try {
            Thread.sleep(Math.max(1L, Math.min(10L, (long) (timeout * 1000))));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        glfwPollEvents();
    }
    public static void glfwPostEmptyEvent() {}

    private static void dispatch(int at) {
        int type = EVENTS.getInt(at), i0 = EVENTS.getInt(at + 4), i1 = EVENTS.getInt(at + 8),
            i2 = EVENTS.getInt(at + 12), i3 = EVENTS.getInt(at + 16);
        double d0 = EVENTS.getDouble(at + 24), d1 = EVENTS.getDouble(at + 32);
        switch (type) {
            case EV_KEY:
                if (i0 >= 0 && i0 <= GLFW_KEY_LAST) KEYS[i0] = (byte) (i2 == GLFW_RELEASE ? GLFW_RELEASE : GLFW_PRESS);
                if (keyCb != null) keyCb.invoke(WINDOW, i0, i1, i2, i3);
                break;
            case EV_CHAR:
                if (charCb != null) charCb.invoke(WINDOW, i0);
                if (charModsCb != null) charModsCb.invoke(WINDOW, i0, i1);
                break;
            case EV_MOUSE_BUTTON:
                if (i0 >= 0 && i0 <= GLFW_MOUSE_BUTTON_LAST) BUTTONS[i0] = (byte) i1;
                if (mouseButtonCb != null) mouseButtonCb.invoke(WINDOW, i0, i1, i2);
                break;
            case EV_CURSOR_POS:
                moveCursor(d0, d1);
                break;
            case EV_CURSOR_MOVE:
                // Captured: an unbounded virtual cursor, like GLFW_CURSOR_DISABLED. Otherwise stay inside the window.
                if (cursorCaptured()) moveCursor(cursorX + d0, cursorY + d1);
                else moveCursor(clamp(cursorX + d0, width - 1), clamp(cursorY + d1, height - 1));
                break;
            case EV_SCROLL:
                if (scrollCb != null) scrollCb.invoke(WINDOW, d0, d1);
                break;
            case EV_WINDOW_SIZE:
                if (i0 <= 0 || i1 <= 0 || (i0 == width && i1 == height)) break;
                width = i0;
                height = i1;
                if (windowSizeCb != null) windowSizeCb.invoke(WINDOW, width, height);
                if (framebufferSizeCb != null) framebufferSizeCb.invoke(WINDOW, width, height);
                break;
            case EV_FOCUS:
                focused = i0 != 0;
                if (windowFocusCb != null) windowFocusCb.invoke(WINDOW, focused);
                break;
            case EV_ICONIFY:
                iconified = i0 != 0;
                if (windowIconifyCb != null) windowIconifyCb.invoke(WINDOW, iconified);
                break;
            case EV_CLOSE:
                shouldClose = true;
                if (windowCloseCb != null) windowCloseCb.invoke(WINDOW);
                break;
            default:
                break;
        }
    }

    private static double clamp(double v, int max) {
        return Math.max(0, Math.min(max, v));
    }

    private static void moveCursor(double x, double y) {
        cursorX = x;
        cursorY = y;
        if (cursorPosCb != null) cursorPosCb.invoke(WINDOW, x, y);
    }

    private static boolean cursorCaptured() {
        int mode = INPUT_MODES.getOrDefault(GLFW_CURSOR, GLFW_CURSOR_NORMAL);
        return mode == GLFW_CURSOR_DISABLED || mode == GLFW_CURSOR_CAPTURED;
    }

    // ---- Input -------------------------------------------------------------------------------

    public static int glfwGetInputMode(long window, int mode) {
        switch (mode) {
            case GLFW_CURSOR: return INPUT_MODES.getOrDefault(mode, GLFW_CURSOR_NORMAL);
            default: return INPUT_MODES.getOrDefault(mode, GLFW_FALSE);
        }
    }
    public static void glfwSetInputMode(long window, int mode, int value) {
        boolean wasCaptured = cursorCaptured();
        INPUT_MODES.put(mode, value);
        if (mode == GLFW_CURSOR && nativeLoaded && wasCaptured != cursorCaptured()) nSetCursorCaptured(cursorCaptured());
    }
    public static boolean glfwRawMouseMotionSupported() {
        return true;
    }

    public static String glfwGetKeyName(int key, int scancode) {
        if (key >= GLFW_KEY_A && key <= GLFW_KEY_Z) return String.valueOf((char) ('a' + key - GLFW_KEY_A));
        if (key >= GLFW_KEY_0 && key <= GLFW_KEY_9) return String.valueOf((char) ('0' + key - GLFW_KEY_0));
        switch (key) {
            case GLFW_KEY_APOSTROPHE: return "'";
            case GLFW_KEY_COMMA: return ",";
            case GLFW_KEY_MINUS: return "-";
            case GLFW_KEY_PERIOD: return ".";
            case GLFW_KEY_SLASH: return "/";
            case GLFW_KEY_SEMICOLON: return ";";
            case GLFW_KEY_EQUAL: return "=";
            case GLFW_KEY_LEFT_BRACKET: return "[";
            case GLFW_KEY_BACKSLASH: return "\\";
            case GLFW_KEY_RIGHT_BRACKET: return "]";
            case GLFW_KEY_GRAVE_ACCENT: return "`";
            default: return null;
        }
    }
    public static int glfwGetKeyScancode(int key) {
        return -1;
    }
    public static int glfwGetKey(long window, int key) {
        return key >= 0 && key <= GLFW_KEY_LAST ? KEYS[key] : GLFW_RELEASE;
    }
    public static int glfwGetMouseButton(long window, int button) {
        return button >= 0 && button <= GLFW_MOUSE_BUTTON_LAST ? BUTTONS[button] : GLFW_RELEASE;
    }
    public static void glfwGetCursorPos(long window, double[] x, double[] y) {
        if (x != null) x[0] = cursorX;
        if (y != null) y[0] = cursorY;
    }
    public static void glfwGetCursorPos(long window, DoubleBuffer x, DoubleBuffer y) {
        if (x != null) x.put(x.position(), cursorX);
        if (y != null) y.put(y.position(), cursorY);
    }
    public static void glfwSetCursorPos(long window, double x, double y) {
        cursorX = x;
        cursorY = y;
    }
    public static long glfwCreateCursor(GLFWImage image, int xhot, int yhot) {
        return 3L;
    }
    public static long glfwCreateStandardCursor(int shape) {
        return 3L;
    }
    public static void glfwDestroyCursor(long cursor) {}
    public static void glfwSetCursor(long window, long cursor) {}

    public static GLFWKeyCallback glfwSetKeyCallback(long window, GLFWKeyCallbackI cb) {
        Object prev = keyCb;
        keyCb = cb;
        return previous(prev, GLFWKeyCallback.class);
    }
    public static GLFWCharCallback glfwSetCharCallback(long window, GLFWCharCallbackI cb) {
        Object prev = charCb;
        charCb = cb;
        return previous(prev, GLFWCharCallback.class);
    }
    public static GLFWCharModsCallback glfwSetCharModsCallback(long window, GLFWCharModsCallbackI cb) {
        Object prev = charModsCb;
        charModsCb = cb;
        return previous(prev, GLFWCharModsCallback.class);
    }
    public static GLFWMouseButtonCallback glfwSetMouseButtonCallback(long window, GLFWMouseButtonCallbackI cb) {
        Object prev = mouseButtonCb;
        mouseButtonCb = cb;
        return previous(prev, GLFWMouseButtonCallback.class);
    }
    public static GLFWCursorPosCallback glfwSetCursorPosCallback(long window, GLFWCursorPosCallbackI cb) {
        Object prev = cursorPosCb;
        cursorPosCb = cb;
        return previous(prev, GLFWCursorPosCallback.class);
    }
    public static GLFWCursorEnterCallback glfwSetCursorEnterCallback(long window, GLFWCursorEnterCallbackI cb) {
        Object prev = cursorEnterCb;
        cursorEnterCb = cb;
        return previous(prev, GLFWCursorEnterCallback.class);
    }
    public static GLFWScrollCallback glfwSetScrollCallback(long window, GLFWScrollCallbackI cb) {
        Object prev = scrollCb;
        scrollCb = cb;
        return previous(prev, GLFWScrollCallback.class);
    }
    public static GLFWDropCallback glfwSetDropCallback(long window, GLFWDropCallbackI cb) {
        Object prev = dropCb;
        dropCb = cb;
        return previous(prev, GLFWDropCallback.class);
    }

    // ---- Text input (IME): no preedit on Android yet ----------------------------------------

    public static GLFWIMEStatusCallback glfwSetIMEStatusCallback(long window, GLFWIMEStatusCallbackI cb) {
        Object prev = imeStatusCb;
        imeStatusCb = cb;
        return previous(prev, GLFWIMEStatusCallback.class);
    }
    public static GLFWPreeditCallback glfwSetPreeditCallback(long window, GLFWPreeditCallbackI cb) {
        Object prev = preeditCb;
        preeditCb = cb;
        return previous(prev, GLFWPreeditCallback.class);
    }
    public static GLFWPreeditCandidateCallback glfwSetPreeditCandidateCallback(long window, GLFWPreeditCandidateCallbackI cb) {
        Object prev = preeditCandidateCb;
        preeditCandidateCb = cb;
        return previous(prev, GLFWPreeditCandidateCallback.class);
    }
    public static void glfwSetPreeditCursorRectangle(long window, int x, int y, int w, int h) {}
    public static void glfwGetPreeditCursorRectangle(long window, int[] x, int[] y, int[] w, int[] h) {
        glfwGetWindowFrameSize(window, x, y, w, h);
    }
    public static void glfwGetPreeditCursorRectangle(long window, IntBuffer x, IntBuffer y, IntBuffer w, IntBuffer h) {
        glfwGetWindowFrameSize(window, x, y, w, h);
    }
    public static void glfwResetPreeditText(long window) {}
    public static IntBuffer glfwGetPreeditCandidate(long window, int index) {
        return null;
    }

    // ---- Clipboard: in-process only for now -------------------------------------------------

    public static String glfwGetClipboardString(long window) {
        return clipboard;
    }
    public static void glfwSetClipboardString(long window, CharSequence text) {
        clipboard = text == null ? "" : text.toString();
    }
    public static void glfwSetClipboardString(long window, ByteBuffer text) {
        if (text == null) {
            clipboard = "";
            return;
        }
        ByteBuffer copy = text.duplicate();
        int end = copy.position();
        while (end < copy.limit() && copy.get(end) != 0) end++;
        byte[] bytes = new byte[end - copy.position()];
        copy.get(bytes);
        clipboard = new String(bytes, StandardCharsets.UTF_8);
    }

    // ---- Joysticks: the activity maps the controller to keys and mouse, so GLFW sees none ---

    public static boolean glfwJoystickPresent(int jid) {
        return false;
    }
    public static FloatBuffer glfwGetJoystickAxes(int jid) {
        return null;
    }
    public static ByteBuffer glfwGetJoystickButtons(int jid) {
        return null;
    }
    public static ByteBuffer glfwGetJoystickHats(int jid) {
        return null;
    }
    public static String glfwGetJoystickName(int jid) {
        return null;
    }
    public static String glfwGetJoystickGUID(int jid) {
        return null;
    }
    public static void glfwSetJoystickUserPointer(int jid, long pointer) {}
    public static long glfwGetJoystickUserPointer(int jid) {
        return 0L;
    }
    public static boolean glfwJoystickIsGamepad(int jid) {
        return false;
    }
    public static GLFWJoystickCallback glfwSetJoystickCallback(GLFWJoystickCallbackI cb) {
        Object prev = joystickCb;
        joystickCb = cb;
        return previous(prev, GLFWJoystickCallback.class);
    }
    public static boolean glfwUpdateGamepadMappings(ByteBuffer mappings) {
        return true;
    }
    public static String glfwGetGamepadName(int jid) {
        return null;
    }
    public static boolean glfwGetGamepadState(int jid, GLFWGamepadState state) {
        return false;
    }
}
