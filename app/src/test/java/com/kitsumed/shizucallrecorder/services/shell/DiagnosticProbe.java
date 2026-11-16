package com.kitsumed.shizucallrecorder.services.shell;

/** A real child process for pipe, EOF, exit-status and timeout regression tests. */
public final class DiagnosticProbe {
    public static void main(String[] arguments) throws Exception {
        switch (arguments[0]) {
            case "large":
                System.out.write(new byte[Integer.parseInt(arguments[1])]);
                break;
            case "delayed-exit":
                System.out.print("before-exit");
                System.out.close();
                System.err.close();
                Thread.sleep(600);
                break;
            case "timeout":
                System.out.print("partial-evidence");
                System.out.flush();
                Thread.sleep(15000);
                break;
            case "failure":
                System.err.println("Permission denied");
                System.exit(7);
                break;
            default:
                throw new IllegalArgumentException(arguments[0]);
        }
    }
}
