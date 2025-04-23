module org.cikit.oci.interceptor {
    requires transitive kotlin.stdlib;
    requires transitive com.github.ajalt.mordant.core;
    requires transitive com.github.ajalt.clikt;
    requires transitive org.cikit.libjail;

    exports org.cikit.oci;
    exports org.cikit.oci.jail;
}
