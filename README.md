# JTerm — Java TTY UI Toolkit

Pure-Java terminal UI toolkit with double-buffered diff refresh, widget library,
layout managers, and per-character ANSI styling.

## Install from Maven Central

```xml
<dependency>
    <groupId>io.jterm</groupId>
    <artifactId>jterm</artifactId>
    <version>0.1.2</version>
</dependency>
```

Gradle: `implementation("io.jterm:jterm:0.1.2")` · [Browse on Maven Central →](https://central.sonatype.com/artifact/io.jterm/jterm)

## Quick Start

```bash
mvn package
java -cp target/jterm-0.1.3-SNAPSHOT.jar io.jterm.demo.HelloWorld
```

## Features

- **Three-layer architecture**: Terminal → Screen (double-buffered) → GUI (widgets)
- **Diff-based refresh**: Only transmits changed cells with SGR state batching
- **Per-character styling**: ANSI 16-color, 256-color, and 24-bit RGB per cell
- **Widget library**: Label, Button, TextBox, CheckBox, ListBox, Table, ProgressBar, DataGrid, and more
- **Layout managers**: LinearLayout, BorderLayout, GridLayout
- **Window management**: Z-ordered windows, modal dialogs, focus management
- **Zero native deps**: Pure Java, works on any xterm-compatible terminal
- **Java 26**: Records, sealed interfaces, virtual threads for input polling

## Demos

- `HelloWorld` — styled label, interactive button counter, quit button
- `DashboardDemo` — full-screen layout with ListBox, Table, ProgressBar, and Tab navigation

```bash
java -cp target/jterm-0.1.3-SNAPSHOT.jar io.jterm.demo.DashboardDemo
```

## Architecture

```
Terminal (raw ANSI/VT100) → Screen (double buffer + diff) → GUI (widgets + layout)
```

## License

Apache 2.0