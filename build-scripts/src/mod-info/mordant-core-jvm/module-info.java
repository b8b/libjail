module com.github.ajalt.mordant.core {
    requires java.management;

    requires transitive com.github.ajalt.colormath;
    requires transitive kotlin.stdlib;

    uses com.github.ajalt.mordant.terminal.TerminalInterfaceProvider;

    exports com.github.ajalt.mordant.animation;
    exports com.github.ajalt.mordant.animation.progress;
    exports com.github.ajalt.mordant.input;
    exports com.github.ajalt.mordant.internal;
    exports com.github.ajalt.mordant.internal.gen;
    exports com.github.ajalt.mordant.platform;
    exports com.github.ajalt.mordant.rendering;
    exports com.github.ajalt.mordant.table;
    exports com.github.ajalt.mordant.terminal;
    exports com.github.ajalt.mordant.terminal.terminalinterface;
    exports com.github.ajalt.mordant.widgets;
    exports com.github.ajalt.mordant.widgets.progress;
}
