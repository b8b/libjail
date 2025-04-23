module com.github.ajalt.mordant.ffm {
	requires transitive kotlin.stdlib;

	requires com.github.ajalt.mordant.core;

	provides com.github.ajalt.mordant.terminal.TerminalInterfaceProvider with com.github.ajalt.mordant.terminal.terminalinterface.ffm.TerminalInterfaceProviderFfm;

	exports com.github.ajalt.mordant.terminal.terminalinterface.ffm;
}
