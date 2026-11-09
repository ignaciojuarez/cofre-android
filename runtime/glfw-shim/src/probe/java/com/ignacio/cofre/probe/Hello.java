package com.ignacio.cofre.probe;

/** Smallest possible game: proves the :game process boots HotSpot. Debug builds only. */
public final class Hello {
    private Hello() {}

    public static void main(String[] args) {
        System.out.println("[Cofre] jvm started java.version=" + System.getProperty("java.version")
            + " vm=" + System.getProperty("java.vm.name") + " arch=" + System.getProperty("os.arch")
            + " cpus=" + Runtime.getRuntime().availableProcessors() + " cwd=" + System.getProperty("user.dir"));
        // "exit=N" leaves through System.exit, the path the game takes; otherwise main just returns.
        for (String arg : args) {
            if (arg.startsWith("exit=")) System.exit(Integer.parseInt(arg.substring(5)));
        }
    }
}
