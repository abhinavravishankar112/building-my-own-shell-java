masBuilding my own shell (Java)

A small interactive shell written in Java. It supports basic built-in commands, running external executables, **tab completion**, and **command history** (with persistence).

## Features

### Built-in commands
- `pwd` — print the current working directory
- `cd <path>` — change directory
   - supports `~` and `~/...`
- `echo <args...>` — print arguments
- `type <command>` — show whether a command is a shell builtin or where it resolves on `$PATH`
- `history` — list previously entered commands
- `exit` — exit the shell

### Running external commands
If the command is not a builtin, the shell searches for an executable in your `$PATH` and runs it using `ProcessBuilder`, with the process:
- executed in the shell’s current directory
- attached to your terminal (`inheritIO()`)

### Command completion (Tab)
Press `TAB` to complete:

**Command completion**
- Completes builtins (`pwd`, `cd`, `echo`, `type`, `history`, `exit`)
- Completes executables available on `$PATH`

**File & directory completion**
- Completes files/directories relative to the current directory
- Supports `./`, `../`, and `~/`
- Adds `/` after directories

**Multiple matches**
- If more than one match exists, the shell prints all candidates and redraws your current prompt and buffer.

**Partial completion**
- If multiple matches share a longer common prefix, the shell completes up to that prefix.

### History navigation (Up/Down arrows)
- `↑` goes to older commands
- `↓` goes back toward newer commands / the current in-progress line

### History persistence
- On startup, history is loaded from: `~/.my_shell_history`
- Each non-empty command is appended to this file as you run it
- History length is limited in memory (default: 2000 entries)

## Requirements

- Java 11+ recommended
- macOS/Linux terminal (this project uses `stty` to enable raw keyboard input)

> On Windows, `stty` is usually not available in `cmd.exe` / PowerShell.  
> If you want Windows support, run inside WSL/Git Bash or adapt the raw-mode input layer.

## How to Run

Compile:

```bash
javac Main.java
```

Run:

```bash
java Main
```

You should see a prompt:

```text
$ 
```

## Usage Examples

Print the current directory:

```text
$ pwd
/your/current/path
```

Change directory:

```text
$ cd ..
$ cd ~
$ cd ~/Downloads
```

Check how a command resolves:

```text
$ type cd
cd is a shell builtin

$ type git
git is /usr/bin/git
```

Run an external command:

```text
$ ls
$ git status
```

History:

```text
$ history
    1  pwd
    2  cd ~
    3  ls
```

Tab completion:

```text
$ ec<TAB>
$ echo 
```

```text
$ cd Do<TAB>
$ cd Downloads/
```

## License

MIT — see `LICENSE`.