module com.sun.jna {
    requires java.logging;

    //requires transitive java.desktop;

    exports com.sun.jna;
    exports com.sun.jna.internal;
    exports com.sun.jna.ptr;
    exports com.sun.jna.win32;
}
