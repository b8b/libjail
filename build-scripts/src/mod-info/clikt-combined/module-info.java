module com.github.ajalt.clikt {
    requires transitive kotlin.stdlib;
    requires transitive com.github.ajalt.mordant.core;

    exports com.github.ajalt.clikt.command;
    exports com.github.ajalt.clikt.completion;
    exports com.github.ajalt.clikt.core;
    exports com.github.ajalt.clikt.internal;
    exports com.github.ajalt.clikt.output;
    exports com.github.ajalt.clikt.parameters.arguments;
    exports com.github.ajalt.clikt.parameters.groups;
    exports com.github.ajalt.clikt.parameters.internal;
    exports com.github.ajalt.clikt.parameters.options;
    exports com.github.ajalt.clikt.parameters.transform;
    exports com.github.ajalt.clikt.parameters.types;
    exports com.github.ajalt.clikt.parsers;
    exports com.github.ajalt.clikt.sources;
    exports com.github.ajalt.clikt.testing;
}
