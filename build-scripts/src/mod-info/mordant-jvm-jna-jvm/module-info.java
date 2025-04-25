module com.github.ajalt.mordant.jna {
	requires transitive com.sun.jna;
	requires transitive kotlin.stdlib;

	requires com.github.ajalt.mordant.core;

	provides com.github.ajalt.mordant.terminal.TerminalInterfaceProvider with com.github.ajalt.mordant.terminal.terminalinterface.jna.TerminalInterfaceProviderJna;

	exports com.github.ajalt.mordant.terminal.terminalinterface.jna;
}
